package app.brucia.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

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

    fun promote(part: File, destination: File) {
        if (!part.isFile || part.length() == 0L) throw IOException("Empty or missing download")
        try {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
