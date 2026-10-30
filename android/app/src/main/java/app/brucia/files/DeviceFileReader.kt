package app.brucia.files

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class FileReadResult(val bytes: ByteArray? = null, val error: String = "")

/** Serialized, bounded downloads. Only a matching request may supply bytes or completion. */
class DeviceFileReader(
    private val maxBytes: Int = 16 * 1024 * 1024,
    private val timeoutMs: Long = 120_000,
) {
    private class Pull(val id: Int) {
        val bytes = ByteArrayOutputStream()
        var sequence = 0
        val checksum = CRC32()
        val result = CompletableDeferred<FileReadResult>()
    }
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var active: Pull? = null

    init { require(maxBytes >= 0 && timeoutMs > 0) }

    suspend fun read(id: Int, send: () -> Unit): FileReadResult {
        val epoch = generation.get()
        return mutex.withLock {
            val pull = Pull(id)
            val admitted = synchronized(this) {
                if (epoch != generation.get()) false else { active = pull; true }
            }
            if (!admitted) return@withLock FileReadResult(error = "Disconnected")
            try {
                withTimeoutOrNull(timeoutMs) {
                    send()
                    pull.result.await()
                } ?: FileReadResult(error = "File download timed out")
            } finally {
                synchronized(this) { if (active === pull) active = null }
            }
        }
    }

    @Synchronized
    fun chunk(id: Int, sequence: Int, encoded: String) {
        val pull = active?.takeIf { it.id == id && !it.result.isCompleted } ?: return
        if (sequence != pull.sequence) {
            pull.result.complete(FileReadResult(error = "Missing or duplicate file chunk"))
            return
        }
        try {
            // Bound encoded input before allocating a decoded copy.
            require(encoded.length <= 8192) { "File chunk too large" }
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size <= maxBytes - pull.bytes.size()) { "File exceeds download limit" }
            pull.bytes.write(bytes)
            pull.checksum.update(bytes)
            pull.sequence++
        } catch (error: IllegalArgumentException) {
            pull.result.complete(FileReadResult(error = error.message ?: "Invalid file chunk"))
        }
    }

    @Synchronized
    fun reply(id: Int, ok: Boolean, size: Long, message: String, checksum: Long = -1) {
        val pull = active?.takeIf { it.id == id && !it.result.isCompleted } ?: return
        val result = when {
            !ok -> FileReadResult(error = message.ifBlank { "File download failed" })
            size != pull.bytes.size().toLong() -> FileReadResult(error = "File size mismatch")
            checksum != pull.checksum.value -> FileReadResult(error = "File checksum missing or mismatched; update firmware if needed")
            else -> FileReadResult(bytes = pull.bytes.toByteArray())
        }
        pull.result.complete(result)
    }

    @Synchronized
    fun disconnect() {
        generation.incrementAndGet()
        active?.result?.complete(FileReadResult(error = "Disconnected"))
    }
}
