package app.brucia.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.security.DigestOutputStream
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Another download of the same destination is running; this request touched nothing. */
class DownloadBusyException(message: String) : IOException(message)

/** Shared bounded copy path for release downloads; never promotes partial data. */
object DownloadIO {
    fun copy(input: InputStream, output: OutputStream, expected: Long, limit: Long,
             isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): Long {
        if (expected > limit) throw IOException("Download exceeds size limit")
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var last = 0L
        while (true) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException("Download stream made no progress")
            if (count > limit - done) throw IOException("Download exceeds size limit")
            output.write(buffer, 0, count)
            done += count
            if (done - last >= 256 * 1024) { onProgress(done, expected); last = done }
        }
        if (isCancelled()) throw InterruptedException("cancelled")
        if (done == 0L || (expected >= 0 && done != expected)) throw IOException("Incomplete download")
        onProgress(done, expected)
        return done
    }

    /** [copy] while hashing; throws [IntegrityException] before the caller can use the output. */
    fun copyVerified(input: InputStream, output: OutputStream, expected: Long, limit: Long, integrity: AssetIntegrity,
                     isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): Long {
        val declared = integrity.size
        if (declared != null && expected >= 0 && expected != declared)
            throw IntegrityException("Size mismatch: server reports $expected bytes, release lists $declared")
        val digest = AssetIntegrity.sha256()
        val done = copy(input, DigestOutputStream(output, digest), expected, limit, isCancelled, onProgress)
        integrity.check(done, digest.digest())
        return done
    }

    fun promote(part: File, destination: File) {
        if (!part.isFile || part.length() == 0L) throw IOException("Empty or missing download")
        try {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Throws for a non-2xx response; [notFound] replaces the generic message for 404. */
    fun checkStatus(conn: HttpURLConnection, notFound: String? = null) {
        val code = conn.responseCode
        if (code / 100 != 2) throw IOException(if (code == 404 && notFound != null) notFound else "HTTP $code")
    }

    /** Destinations with a download in progress (absolute paths). */
    private val activeFiles = HashSet<String>()

    /**
     * Stream [connect]'s body into [dir]/[name]. One download per destination at a time: a second
     * request throws [DownloadBusyException] before touching the file or the network, so it can
     * neither delete nor replace the file the first caller is about to install. The previous
     * destination is removed before any IO, the body goes to a unique `.part` file, and only data
     * matching [integrity] is promoted. Cancellation is observed between reads.
     */
    fun downloadFile(dir: File, name: String, limit: Long, integrity: AssetIntegrity,
                     connect: () -> HttpURLConnection, isCancelled: () -> Boolean,
                     onProgress: (Long, Long) -> Unit,
                     sink: (File) -> OutputStream = { FileOutputStream(it) }): File {
        val key = File(dir, name).absolutePath
        synchronized(activeFiles) {
            if (!activeFiles.add(key)) throw DownloadBusyException("An update download is already in progress")
        }
        try {
            return downloadFileOnce(dir, name, limit, integrity, connect, isCancelled, onProgress, sink)
        } finally {
            synchronized(activeFiles) { activeFiles.remove(key) }
        }
    }

    private fun downloadFileOnce(dir: File, name: String, limit: Long, integrity: AssetIntegrity,
                                 connect: () -> HttpURLConnection, isCancelled: () -> Boolean,
                                 onProgress: (Long, Long) -> Unit, sink: (File) -> OutputStream): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create update cache")
        val dest = File(dir, name)
        // Never leave an earlier download where a failed verification could be mistaken for it.
        if (dest.exists() && !dest.delete()) throw IOException("Cannot clear previous update file")
        val conn = connect()
        try {
            checkStatus(conn)
            val total = conn.contentLengthLong
            val part = File.createTempFile("brucia-update-", ".part", dir)
            try {
                conn.inputStream.use { input ->
                    sink(part).use { out -> copyVerified(input, out, total, limit, integrity, isCancelled, onProgress) }
                }
                promote(part, dest)
                return dest
            } finally { part.delete() }
        } finally { conn.disconnect() }
    }

    /** [connect]'s body in memory, returned only when it matches [integrity]. */
    fun downloadBytes(connect: () -> HttpURLConnection, limit: Long, integrity: AssetIntegrity,
                      isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit, notFound: String? = null): ByteArray {
        val conn = connect()
        try {
            checkStatus(conn, notFound)
            val total = conn.contentLengthLong
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input -> copyVerified(input, out, total, limit, integrity, isCancelled, onProgress) }
            return out.toByteArray()
        } finally { conn.disconnect() }
    }

    /**
     * Download step of an operation that can end underneath it (flash abort, owner shutdown, job
     * cancellation). [fetch] stops at the next read once [owned] is false, progress is no longer
     * reported, and a result is returned only while still owned; otherwise [InterruptedException].
     */
    fun <T> owned(owned: () -> Boolean, onProgress: (Long, Long) -> Unit,
                  fetch: (onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) -> T): T {
        val result = fetch({ done, total -> if (owned()) onProgress(done, total) }, { !owned() })
        if (!owned()) throw InterruptedException("cancelled")
        return result
    }
}
