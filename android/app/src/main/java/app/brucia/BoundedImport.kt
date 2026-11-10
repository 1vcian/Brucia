package app.brucia

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** An imported file is larger than the limit for its kind; nothing was loaded. */
class ImportTooLargeException(val limit: Long) : IOException("File too large (max ${BoundedImport.describe(limit)})")

/** Bounded whole-file reads for user-picked documents (IR remotes, PCAP captures). */
object BoundedImport {
    /** Flipper `.ir` files, including the large universal remotes, are well under this. */
    const val MAX_IR_BYTES = 4L * 1024 * 1024

    /**
     * Read [input] fully, throwing [ImportTooLargeException] as soon as more than [limit] bytes
     * arrive (exactly [limit] is accepted) and [InterruptedException] when [isCancelled] turns
     * true between reads, so an oversized or abandoned file is never buffered whole.
     */
    fun read(input: InputStream, limit: Long, isCancelled: () -> Boolean = { false }): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (isCancelled()) throw InterruptedException("Import cancelled")
            val n = input.read(buf)
            if (n < 0) break
            if (n > limit - total) throw ImportTooLargeException(limit)
            total += n
            out.write(buf, 0, n)
        }
        if (isCancelled()) throw InterruptedException("Import cancelled")
        return out.toByteArray()
    }

    /** Read and parse an IR file off the caller's thread budget: bounded bytes, UTF-8, [parse]. */
    fun <T> readIr(input: InputStream, isCancelled: () -> Boolean = { false }, parse: (String) -> T): T =
        parse(read(input, MAX_IR_BYTES, isCancelled).toString(Charsets.UTF_8))

    fun describe(limit: Long): String =
        if (limit % (1024 * 1024) == 0L) "${limit / (1024 * 1024)} MiB" else "$limit bytes"
}
