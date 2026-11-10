package app.brucia.wordlist

import android.content.Context
import android.net.Uri
import app.brucia.update.AssetIntegrity
import app.brucia.update.DownloadIO
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class WordlistInfo(val name: String, val lines: Int, val sizeBytes: Long)

/** Another import or download of the same wordlist is running; this request touched nothing. */
class WordlistBusyException(message: String) : IOException(message)

/** Stores imported wordlists on the phone (rockyou-scale is fine: phone storage). */
object WordlistStore {
    /** Largest wordlist accepted from a download or a manual import. */
    const val MAX_BYTES = 512L * 1024 * 1024

    private fun dir(ctx: Context): File = File(ctx.filesDir, "wordlists").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /**
     * Copy the document at [uri] into the store as [suggestedName]. The bytes are staged in a
     * unique `.part` file, checked by [DictionaryTextCheck], and promoted only once complete;
     * on any failure (provider error, size limit, invalid text, cancellation, busy name) the
     * previous list with that name is left as it was. Not a power-loss guarantee.
     */
    fun importFrom(ctx: Context, uri: Uri, suggestedName: String, isCancelled: () -> Boolean = { false }): WordlistInfo =
        importInto(dir(ctx), suggestedName, isCancelled) {
            ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file")
        }

    fun list(ctx: Context): List<WordlistInfo> = listIn(dir(ctx))

    fun has(ctx: Context, name: String): Boolean = File(dir(ctx), sanitize(name)).exists()

    /**
     * Stream a wordlist URL to phone storage, following redirects (GitHub releases
     * redirect to a CDN). Downloads to a `.part` file and renames on success so a
     * half-finished download never looks complete. Returns the finished [WordlistInfo].
     */
    fun download(
        ctx: Context,
        url: String,
        name: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
        integrity: AssetIntegrity? = null,
    ): WordlistInfo {
        val dest = File(dir(ctx), sanitize(name))
        return exclusive(dest) {
            DictionaryDownload.store(dest, url, MAX_BYTES, isCancelled, onProgress, integrity = integrity)
            info(dest)
        }
    }

    fun delete(ctx: Context, name: String): Boolean = File(dir(ctx), sanitize(name)).delete()

    // --- JVM-testable core -------------------------------------------------------------

    /** Destinations with an import or download in progress (absolute paths). */
    private val active = HashSet<String>()

    /** Runs [block] as the only import/download of [dest]; a concurrent one fails before any IO. */
    internal fun <T> exclusive(dest: File, block: () -> T): T {
        val key = dest.absolutePath
        synchronized(active) {
            if (!active.add(key)) throw WordlistBusyException("${dest.name} is already being imported or downloaded")
        }
        try {
            return block()
        } finally {
            synchronized(active) { active.remove(key) }
        }
    }

    internal fun importInto(
        dir: File,
        suggestedName: String,
        isCancelled: () -> Boolean,
        limit: Long = MAX_BYTES,
        sink: (File) -> OutputStream = { FileOutputStream(it) },
        open: () -> InputStream,
    ): WordlistInfo {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create the wordlist folder")
        val dest = File(dir, sanitize(suggestedName))
        return exclusive(dest) {
            val part = File.createTempFile("import-", ".part", dir)
            try {
                val check = DictionaryTextCheck()
                open().use { input ->
                    DictionaryTextOutputStream(sink(part), check).use { out ->
                        copyBounded(input, out, limit, isCancelled)
                        out.flush()
                    }
                }
                val lines = check.finish()
                if (check.bytes == 0L) throw IOException("The selected file is empty")
                if (part.length() != check.bytes) throw IOException("Short write while importing ${dest.name}")
                if (isCancelled()) throw InterruptedException("Import cancelled")
                DownloadIO.promote(part, dest)
                WordlistInfo(dest.name, lines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), dest.length())
            } finally {
                part.delete()
            }
        }
    }

    private fun copyBounded(input: InputStream, out: OutputStream, limit: Long, isCancelled: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            if (isCancelled()) throw InterruptedException("Import cancelled")
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) throw IOException("The selected file made no progress")
            if (n > limit - done) throw IOException("Wordlist exceeds ${limit / (1024 * 1024)} MiB")
            out.write(buf, 0, n)
            done += n
        }
    }

    internal fun listIn(dir: File): List<WordlistInfo> =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.map { info(it) }
            ?.sortedBy { it.name } ?: emptyList()

    /** Non-empty lines (ended by `\n` or `\r`, as readLine) counted in bounded memory. */
    internal fun info(f: File): WordlistInfo {
        var n = 0L
        var lineBytes = 0L
        val buf = ByteArray(64 * 1024)
        f.inputStream().use { input ->
            while (true) {
                val count = input.read(buf)
                if (count < 0) break
                for (i in 0 until count) {
                    val b = buf[i]
                    if (b == LF || b == CR) { if (lineBytes > 0) n++; lineBytes = 0 } else lineBytes++
                }
            }
        }
        if (lineBytes > 0) n++
        return WordlistInfo(f.name, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), f.length())
    }

    private const val LF: Byte = '\n'.code.toByte()
    private const val CR: Byte = '\r'.code.toByte()

    internal fun sanitize(s: String): String {
        val base = s.substringAfterLast('/')
        val clean = base.replace(Regex("[^A-Za-z0-9._-]"), "_").takeUnless { it.isEmpty() || it == "." || it == ".." }
            ?: "wordlist.txt"
        // Staging files end in .part and are never listed; a list must not look like one.
        return if (clean.endsWith(".part")) "$clean.txt" else clean
    }
}
