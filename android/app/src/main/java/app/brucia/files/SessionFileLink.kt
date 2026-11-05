package app.brucia.files

import app.brucia.session.CommandSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32

interface FileTransferLink {
    fun isCurrent(): Boolean
    suspend fun read(path: String): ByteArray
    suspend fun write(path: String, bytes: ByteArray)
}

/** Verified reads and transactional uploads on one session, sharing the wire mutex with captures. */
class SessionFileLink(
    private val session: CommandSession,
    private val reader: DeviceFileReader,
    private val mutex: Mutex,
    private val stillCurrent: () -> Boolean,
    private val writeTimeoutMs: Long = 8_000,
    private val transactionalUploads: Boolean = false,
    private val commitTimeoutMs: Long = 130_000,
) : FileTransferLink {
    override fun isCurrent() = stillCurrent() && !session.isClosed

    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw IOException("Connection changed; transfer stopped.")
    }

    override suspend fun read(path: String): ByteArray {
        DeviceFilePush.pathError(path)?.let { throw IOException(it) }
        checkCurrent()
        return mutex.withLock {
            checkCurrent()
            val id = session.nextId()
            try {
                val result = reader.read(id) {
                    session.expectJson(id) { ok, data ->
                        reader.reply(id, ok, data?.optLong("size", -1) ?: -1,
                            data?.optString("msg")?.ifBlank { data.optString("out") } ?: "",
                            data?.optLong("crc32", -1) ?: -1)
                    }
                    session.transmit("fs_read $path", id)
                }
                checkCurrent()
                result.bytes ?: throw IOException(result.error)
            } finally { session.release(id) }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val destination = DeviceFilePush.uploadPath(path)
        if (bytes.size > MAX_BYTES) throw IOException("File exceeds 16 MiB transfer limit")
        if (!transactionalUploads) throw IOException("Update device firmware to enable transactional uploads")
        checkCurrent()
        mutex.withLock {
            checkCurrent()
            val token = UUID.randomUUID().toString().replace("-", "")
            val crc = CRC32().apply { update(bytes) }.value
            var committed = false
            try {
                // Begin may recover a previous transaction, including a full CRC readback.
                uploadReply("fs_upload_begin $token ${bytes.size} $crc $destination", token, 0, commitTimeoutMs)
                var offset = 0
                while (offset < bytes.size) {
                    checkCurrent()
                    val end = minOf(bytes.size, offset + DeviceFilePush.MAX_RAW_CHUNK)
                    val chunk = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))
                    uploadReply("fs_upload_chunk $token $offset $chunk", token, end.toLong())
                    offset = end
                }
                try {
                    val reply = uploadReply("fs_upload_commit $token", token, bytes.size.toLong(), commitTimeoutMs)
                    if (reply.opt("committed") != true || unsigned(reply, "crc32") != crc)
                        throw IOException("Commit acknowledgement mismatch")
                } catch (e: IOException) {
                    throw IOException("Commit unconfirmed; verify the destination before retrying. ${e.message}", e)
                }
                committed = true
            } finally {
                // Abort only on this link. After a sent commit, cancellation/timeout cannot prove rollback.
                if (!committed && isCurrent()) withContext(NonCancellable) {
                    withTimeoutOrNull(minOf(writeTimeoutMs, 2_000)) {
                        if (isCurrent()) session.awaitJson("fs_upload_abort $token", minOf(writeTimeoutMs, 2_000))
                    }
                }
            }
        }
    }

    private suspend fun uploadReply(command: String, token: String, size: Long, timeout: Long = writeTimeoutMs): JSONObject {
        checkCurrent()
        val (ok, data) = session.awaitJson(command, timeout)
        checkCurrent()
        if (!ok) throw IOException(data?.optString("msg")?.takeIf { it.isNotBlank() }
            ?: data?.optString("out")?.takeIf { it.isNotBlank() } ?: "Upload failed")
        if (data == null || data.opt("token") != token || unsigned(data, "size") != size)
            throw IOException("Upload acknowledgement token/size mismatch")
        return data
    }

    private fun unsigned(data: JSONObject, field: String): Long? {
        // Explicit Any?: Kotlin 2.4 reports a false non-exhaustive 'when' on the platform type.
        val value: Any? = data.opt(field)
        return when (value) {
            is Int -> value.toLong().takeIf { it >= 0 }
            is Long -> value.takeIf { it in 0..0xFFFFFFFFL }
            else -> null
        }
    }

    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
