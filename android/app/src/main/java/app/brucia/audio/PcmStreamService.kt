package app.brucia.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Whether the device reports per-frame accepted counts (fm_audio_data `accepted`). */
enum class PcmDelivery { PENDING, REPORTED, UNKNOWN }

data class PcmStreamState(
    val active: Boolean = false, val progress: Float = 0f, val message: String = "",
    val level: Float = 0f, val elapsedMs: Long = 0,
    val delivery: PcmDelivery = PcmDelivery.PENDING,
    /** Device-reported cumulative loss after acceptance (FM `lost`); 0 when unknown. */
    val deviceDropped: Long = 0,
)
data class PcmStreamSpec(val fmKHz: Int? = null, val deviceMic: Boolean = false) {
    val frameSamples: Int get() = if (fmKHz == null) 2048 else 1024
}
data class PcmStreamSource(val reader: PcmSource.PcmReader, val totalSamples: Long? = null)

/**
 * Reply to one PCM data frame. [accepted] (samples queued from the start of the frame),
 * [received] (samples the device decoded), [free] (device buffer space after the frame) and
 * [lost] (cumulative samples the device dequeued but could not output) are null when the
 * firmware does not report them; then delivery is unknown. A malformed count is -1.
 */
data class PcmDataReply(
    val ok: Boolean, val message: String,
    val accepted: Int? = null, val received: Int? = null, val free: Int? = null, val lost: Long? = null,
)

/** One captured session; data/stop must never use a replacement device. */
interface PcmStreamLink {
    fun isCurrent(): Boolean
    suspend fun begin(): Pair<Boolean, String>
    suspend fun data(samples: ShortArray, count: Int): PcmDataReply
    suspend fun stop(): Pair<Boolean, String>
}

/** Owns preparation, streaming and finalization. One wire lease orders old stop before new begin.
 * A blocking source must return before that lease can be released; cancellation cannot forcibly
 * interrupt an arbitrary reader. No obsolete run can publish state or send data after returning.
 */
class PcmStreamService(
    private val scope: CoroutineScope,
    private val connect: (PcmStreamSpec) -> PcmStreamLink,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val state = MutableStateFlow(PcmStreamState())
    val status = state.asStateFlow()
    private val wireLease = Mutex()
    private var generation = 0L
    private var job: Job? = null

    /** Duplicate starts are ignored until Stop; preparation is already an active operation. */
    @Synchronized fun start(
        spec: PcmStreamSpec,
        preparing: String,
        playing: String,
        prepare: suspend (checkActive: () -> Unit) -> PcmStreamSource? = { null },
    ) {
        if (state.value.active) return
        val owner = ++generation
        if (spec.fmKHz != null && spec.fmKHz !in 87500..108000 || spec.deviceMic && spec.fmKHz == null) {
            state.value = PcmStreamState(message = "Invalid audio stream parameters."); return
        }
        val link = try { connect(spec) } catch (e: Exception) {
            state.value = PcmStreamState(message = e.message ?: "Not connected."); return
        }
        state.value = PcmStreamState(active = true, message = preparing)
        job = scope.launch(dispatcher) {
            var source: PcmStreamSource? = null
            var failure: String? = null
            try {
                val context = currentCoroutineContext()
                val check = {
                    context.ensureActive()
                    if (!link.isCurrent()) throw IOException("Connection changed.")
                    synchronized(this@PcmStreamService) { if (owner != generation) throw CancellationException() }
                    Unit
                }
                wireLease.withLock {
                    check()
                    var attempted = false
                    try {
                        source = prepare(check)
                        check()
                        if (!spec.deviceMic && source == null) throw IOException("Audio source unavailable.")
                        attempted = true // even a timed-out begin may have started the hardware
                        val (ok, text) = link.begin()
                        check()
                        if (!ok) throw IOException(text.ifBlank { "Audio stream start failed." })
                        update(owner) { it.copy(message = playing) }
                        if (spec.deviceMic) {
                            awaitCancellation() // owns background FM until Stop/disconnect
                        } else {
                            val buffer = ShortArray(spec.frameSamples)
                            val start = nowMs()
                            var sent = 0L
                            var lastFree: Int? = null // device free space at lastFreeAt (reporting firmware)
                            var lastFreeAt = 0L
                            while (true) {
                                check()
                                val count = source!!.reader.read(buffer)
                                check()
                                if (count == -1) break
                                if (count !in 0..buffer.size) throw IOException("Invalid PCM sample count.")
                                if (count == 0) { pause(5); continue }
                                var offset = 0
                                var stalledMs = 0L // drain time waited without any accepted sample
                                while (offset < count) {
                                    val remaining = count - offset
                                    lastFree?.let { free ->
                                        // Estimated space now: reported free plus what drained since the reply.
                                        val drained = (nowMs() - lastFreeAt).coerceAtLeast(0) * PcmSource.SAMPLE_RATE / 1000
                                        val deficit = remaining - free - drained
                                        if (deficit > 0) pause(drainMs(deficit.toInt()))
                                    }
                                    check()
                                    val frame = if (offset == 0) buffer else buffer.copyOfRange(offset, count)
                                    val reply = link.data(frame, remaining)
                                    check()
                                    if (!reply.ok) throw IOException(reply.message.ifBlank { "PCM frame rejected." })
                                    val accepted = reply.accepted
                                    if (accepted == null) {
                                        // Older firmware: an ACK cannot prove the frame was queued.
                                        markUnknown(owner, spec, playing)
                                        sent += remaining
                                        break
                                    }
                                    if (accepted !in 0..remaining || reply.received != null && reply.received != remaining)
                                        throw IOException("Device reported an invalid PCM sample count.")
                                    reported(owner, spec, playing, reply.lost)
                                    offset += accepted
                                    sent += accepted
                                    lastFree = reply.free?.takeIf { it >= 0 }
                                    lastFreeAt = nowMs()
                                    if (offset < count) {
                                        // Device buffer full: resend only the unaccepted remainder once it drains.
                                        if (accepted > 0) stalledMs = 0
                                        else if (stalledMs >= MAX_STALL_MS)
                                            throw IOException("Device stopped accepting audio; ${count - offset} samples not delivered.")
                                        val wait = drainMs(count - offset - (lastFree ?: 0))
                                        if (accepted == 0) stalledMs += wait
                                        pause(wait)
                                    }
                                }
                                val elapsed = sent * 1000 / PcmSource.SAMPLE_RATE
                                update(owner) { it.copy(
                                    elapsedMs = elapsed, level = PcmSource.rmsLevel(buffer, count),
                                    progress = source!!.totalSamples?.takeIf { n -> n > 0 }?.let { n -> (sent.toDouble() / n).toFloat().coerceIn(0f, 1f) } ?: 0f,
                                ) }
                                val wait = elapsed - (nowMs() - start)
                                if (wait > 0) pause(wait)
                            }
                        }
                    } finally {
                        if (attempted && link.isCurrent()) {
                            // Cannot outlive the lease or stop a newer run. Closing a link skips this.
                            withContext(NonCancellable) {
                                try {
                                    val (ok, message) = withTimeout(5_000) { link.stop() }
                                    if (!ok) failure = message.ifBlank { "Audio stop was not confirmed." }
                                } catch (e: Exception) { failure = "Audio stop was not confirmed: ${e.message}" }
                            }
                        }
                        try { source?.reader?.close() }
                        catch (e: Exception) { failure = "Audio source close failed: ${e.message}" }
                    }
                }
            } catch (e: CancellationException) {
                failure = "Stopped."
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "Audio stream failed."
            } finally {
                update(owner) { it.copy(active = false, level = 0f,
                    progress = if (failure == null) 1f else it.progress,
                    message = failure ?: finished(it)) }
            }
        }.also { running -> running.invokeOnCompletion { cause ->
            if (cause is CancellationException) update(owner) { it.copy(active = false, level = 0f, message = "Stopped.") }
        } }
    }

    @Synchronized fun stop(message: String = "Stopped; device cleanup may still be pending.") {
        generation++
        job?.cancel(); job = null
        if (state.value.active) state.value = state.value.copy(active = false, level = 0f, message = message)
    }

    private fun finished(s: PcmStreamState) = when {
        s.deviceDropped > 0 -> "Finished; device dropped ${s.deviceDropped} samples."
        s.delivery == PcmDelivery.UNKNOWN -> "Finished; delivery unknown (firmware does not report accepted samples)."
        else -> "Finished."
    }

    /** Older firmware (FM only): replies cannot prove queued samples; the label says so. */
    private fun markUnknown(owner: Long, spec: PcmStreamSpec, playing: String) {
        if (spec.fmKHz == null) return // speaker/SSTV never reported counts; keep their messages
        update(owner) { if (it.delivery == PcmDelivery.UNKNOWN) it else
            it.copy(delivery = PcmDelivery.UNKNOWN, message = "$playing Delivery unknown (older firmware).") }
    }

    private fun reported(owner: Long, spec: PcmStreamSpec, playing: String, lost: Long?) = update(owner) {
        val dropped = maxOf(it.deviceDropped, lost?.takeIf { n -> n >= 0 } ?: 0)
        if (it.delivery == PcmDelivery.REPORTED && dropped == it.deviceDropped) it
        else it.copy(delivery = if (it.delivery == PcmDelivery.UNKNOWN) it.delivery else PcmDelivery.REPORTED,
            deviceDropped = dropped,
            message = if (dropped > 0 && spec.fmKHz != null) "$playing Device dropped $dropped samples." else it.message)
    }

    @Synchronized private fun update(owner: Long, transform: (PcmStreamState) -> PcmStreamState) {
        if (owner == generation) state.value = transform(state.value)
    }

    companion object {
        /** Drain time waited across consecutive zero-accept replies before the stream fails. */
        const val MAX_STALL_MS = 2_000L
        /** Time for the device to drain [samples] at 16 kHz, bounded to keep retries responsive. */
        fun drainMs(samples: Int): Long = (samples.toLong() * 1000 / PcmSource.SAMPLE_RATE).coerceIn(4L, 128L)
    }
}
