package app.brucia.audio

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded PCM recording. Reaching the limit stops capture but preserves unsaved audio.
 *
 * The producer (the transport read loop) and the UI thread (start/stop/cancel/export) both mutate
 * this buffer. Every mutation and the [state] it publishes happen under this object's lock, so the
 * published state always follows lock order: a producer can never overwrite a newer Stop/Cancel with
 * a value it read earlier. Callers derive UI from [state] and from the [Append] outcome only.
 *
 * Continuity: `mic.pcm` chunks may carry a per-subscription `seq` and a cumulative `dropped`
 * sample count (firmware I2S overruns). Both are optional (older firmware sends neither); absent
 * values make the corresponding [Integrity] field unknown (null), never zero.
 */
class RecordingBuffer(private val limit: Int = 16000 * 2 * 600) {
    /** Outcome of one [append]; derived under the lock, so it is exact for that chunk. */
    enum class Append {
        /** Chunk stored; recording continues. */
        APPENDED,
        /** Chunk stored (possibly truncated) and the limit is now reached; capture stopped. */
        LIMIT_REACHED,
        /** Not recording (never started, stopped, cancelled or already full); chunk ignored. */
        INACTIVE,
        /** Malformed chunk (odd byte count); ignored. */
        REJECTED,
    }

    /**
     * Stream continuity of one recording.
     * [seqGaps]: `seq` discontinuities between recorded chunks (transport/app loss or a stream
     * restart); null when any recorded chunk lacked `seq`.
     * [overrunChunks]/[droppedSamples]: recorded chunks during which the device reported I2S
     * overruns, and the samples it discarded; null when any recorded chunk lacked `dropped`.
     */
    data class Integrity(
        val chunks: Int = 0,
        val seqGaps: Int? = 0,
        val overrunChunks: Int? = 0,
        val droppedSamples: Long? = 0,
    ) {
        /** Splice points known so far: seq gaps plus chunks containing a device overrun. */
        val gapsDetected: Int? get() = seqGaps?.let { it + (overrunChunks ?: 0) }

        fun summary(): String = when {
            chunks == 0 -> "no audio chunks"
            seqGaps == null -> "gaps: unknown (firmware sends no sequence numbers)"
            droppedSamples == null -> "gaps detected: $gapsDetected (device overruns not reported)"
            droppedSamples > 0 -> "gaps detected: $gapsDetected ($droppedSamples samples dropped on device)"
            else -> "gaps detected: $gapsDetected"
        }
    }

    data class State(
        val active: Boolean = false,
        val hasData: Boolean = false,
        /** The current unsaved audio was cut at the size limit. */
        val limitReached: Boolean = false,
        val generation: Long = 0,
        val integrity: Integrity = Integrity(),
    )

    data class Snapshot(val generation: Long, val bytes: ByteArray, val integrity: Integrity)

    private var data = ByteArrayOutputStream()
    private var generation = 0L
    private var limitReached = false
    private var integrity = Integrity()
    private var lastRecordedSeq: Long? = null
    /** Last `dropped` seen on the stream (recording or not), so a recording's loss is a delta. */
    private var streamDropped: Long? = null

    private val _state = MutableStateFlow(State())
    /** Published under the lock after every mutation; UI state derives only from this. */
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var active = false
        private set
    init { require(limit > 0 && limit % 2 == 0) }
    val hasData: Boolean @Synchronized get() = data.size() > 0

    private fun publish() {
        _state.value = State(active, data.size() > 0, limitReached, generation, integrity)
    }

    @Synchronized fun start(): Boolean {
        if (active) return true
        if (data.size() > 0) return false
        generation++
        active = true
        limitReached = false
        integrity = Integrity()
        lastRecordedSeq = null
        publish()
        return true
    }

    /**
     * Offer one PCM chunk. [seq] and [dropped] are the optional `mic.pcm` continuity fields
     * (null = absent). Chunks are offered even while not recording so the overrun baseline stays
     * current. The returned outcome is the only thing callers should derive UI from.
     */
    @Synchronized fun append(pcm: ByteArray, seq: Long? = null, dropped: Long? = null): Append {
        val droppedDelta = dropped?.let { d ->
            // `seq == 0` starts a new subscription whose counter restarts at zero; a counter that
            // went backwards is a restart we missed; an unknown baseline attributes all prior loss
            // to this chunk (over-reports rather than hides).
            val prev = if (seq == 0L) 0L else streamDropped
            if (prev == null || d < prev) d else d - prev
        }
        if (dropped != null) streamDropped = dropped
        if (pcm.size % 2 != 0) return Append.REJECTED
        if (!active) return Append.INACTIVE
        val n = minOf(pcm.size, limit - data.size())
        data.write(pcm, 0, n)
        val last = lastRecordedSeq
        integrity = integrity.copy(
            chunks = integrity.chunks + 1,
            seqGaps = if (seq == null) null else integrity.seqGaps?.let {
                if (last != null && seq != last + 1) it + 1 else it
            },
            overrunChunks = if (droppedDelta == null) null else integrity.overrunChunks?.let {
                if (droppedDelta > 0) it + 1 else it
            },
            droppedSamples = if (droppedDelta == null) null else integrity.droppedSamples?.plus(droppedDelta),
        )
        lastRecordedSeq = seq
        val full = data.size() == limit
        if (full) { active = false; limitReached = true }
        publish()
        return if (full) Append.LIMIT_REACHED else Append.APPENDED
    }

    /** The device restarted `mic.pcm` (new subscription): its counters restart at zero. */
    @Synchronized fun streamRestarted() { streamDropped = 0L }

    @Synchronized fun stop() { active = false; publish() }
    @Synchronized fun snapshot(): Snapshot {
        active = false
        publish()
        return Snapshot(generation, data.toByteArray(), integrity)
    }
    @Synchronized fun commit(snapshot: Snapshot): Boolean {
        if (generation != snapshot.generation) return false
        cancel()
        return true
    }
    @Synchronized fun take(): ByteArray = snapshot().also { commit(it) }.bytes
    @Synchronized fun cancel() {
        active = false
        generation++
        data = ByteArrayOutputStream()
        limitReached = false
        integrity = Integrity()
        lastRecordedSeq = null
        publish()
    }
}
