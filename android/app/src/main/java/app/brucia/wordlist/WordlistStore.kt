package app.brucia.wordlist

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class WordlistInfo(val name: String, val lines: Int, val sizeBytes: Long)

/** Stores imported wordlists on the phone (rockyou-scale is fine: phone storage). */
object WordlistStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "wordlists").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    fun importFrom(ctx: Context, uri: Uri, suggestedName: String): WordlistInfo {
        val dest = File(dir(ctx), sanitize(suggestedName))
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return info(dest)
    }

    fun list(ctx: Context): List<WordlistInfo> =
        dir(ctx).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.map { info(it) }
            ?.sortedBy { it.name } ?: emptyList()

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
    ): WordlistInfo {
        val dest = File(dir(ctx), sanitize(name))
        val part = File(dir(ctx), sanitize(name) + ".part")
        var current = URL(url)
        var conn = open(current)
        var redirects = 0
        while (conn.responseCode in 301..308 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            current = URL(current, loc)
            conn = open(current)
            redirects++
        }
        if (conn.responseCode / 100 != 2) {
            conn.disconnect()
            throw java.io.IOException("HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        try {
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw InterruptedException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            onProgress(done, total); lastReport = done
                        }
                    }
                    onProgress(done, total)
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (dest.exists()) dest.delete()
        part.renameTo(dest)
        return info(dest)
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "Brucia")
    }

    fun delete(ctx: Context, name: String): Boolean = File(dir(ctx), sanitize(name)).delete()

    private fun info(f: File): WordlistInfo {
        var n = 0
        f.bufferedReader().useLines { seq -> for (l in seq) if (l.isNotEmpty()) n++ }
        return WordlistInfo(f.name, n, f.length())
    }

    private fun sanitize(s: String): String {
        val base = s.substringAfterLast('/')
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "wordlist.txt" }
    }
}
