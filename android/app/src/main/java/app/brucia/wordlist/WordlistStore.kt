package app.brucia.wordlist

import android.content.Context
import android.annotation.SuppressLint
import android.net.Uri
import app.brucia.update.AssetIntegrity
import java.io.File

data class WordlistInfo(val name: String, val lines: Int, val sizeBytes: Long)

/** Stores imported wordlists on the phone (rockyou-scale is fine: phone storage). */
object WordlistStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "wordlists").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    fun importFrom(ctx: Context, uri: Uri, suggestedName: String): WordlistInfo {
        val dest = File(dir(ctx), sanitize(suggestedName))
        // Closed by use{} below; AGP 8.13 lint misses it here even on a non-null receiver.
        @SuppressLint("Recycle")
        val input = ctx.contentResolver.openInputStream(uri)!!
        input.use { dest.outputStream().use { out -> it.copyTo(out) } }
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
        integrity: AssetIntegrity? = null,
    ): WordlistInfo {
        val dest = File(dir(ctx), sanitize(name))
        DictionaryDownload.store(dest, url, 512L * 1024 * 1024, isCancelled, onProgress, integrity = integrity)
        return info(dest)
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
