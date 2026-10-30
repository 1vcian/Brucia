package app.brucia.audio

import java.io.ByteArrayOutputStream

/** Bounded PCM recording. Reaching the limit stops capture but preserves unsaved audio. */
class RecordingBuffer(private val limit: Int = 16000 * 2 * 600) {
    private var data = ByteArrayOutputStream()
    private var generation = 0L
    data class Snapshot(val generation: Long, val bytes: ByteArray)
    @Volatile var active = false
        private set
    init { require(limit > 0 && limit % 2 == 0) }
    val hasData: Boolean @Synchronized get() = data.size() > 0
    @Synchronized fun start(): Boolean {
        if (active) return true
        if (hasData) return false
        generation++
        active = true
        return true
    }
    @Synchronized fun append(pcm: ByteArray) {
        if (!active || pcm.size % 2 != 0) return
        data.write(pcm, 0, minOf(pcm.size, limit - data.size()))
        if (data.size() == limit) active = false
    }
    @Synchronized fun stop() { active = false }
    @Synchronized fun snapshot(): Snapshot {
        active = false
        return Snapshot(generation, data.toByteArray())
    }
    @Synchronized fun commit(snapshot: Snapshot): Boolean {
        if (generation != snapshot.generation) return false
        cancel()
        return true
    }
    @Synchronized fun take(): ByteArray = snapshot().also { commit(it) }.bytes
    @Synchronized fun cancel() { active = false; generation++; data = ByteArrayOutputStream() }
}
