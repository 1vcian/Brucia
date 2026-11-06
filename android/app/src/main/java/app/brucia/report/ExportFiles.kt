package app.brucia.report

import android.content.Context
import app.brucia.utils.FileRetention
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Report / session exports handed to the share sheet. Each export is a new timestamped file; only
 * the newest [KEEP] of a kind stay (they used to pile up in the app folder forever). Failures are
 * thrown as [IOException] for the caller to show, never as a crash.
 */
object ExportFiles {
    const val KEEP = 10

    fun write(dir: File, prefix: String, ext: String, text: String, now: Long = System.currentTimeMillis()): File {
        val file = writeNamed(dir, "$prefix$now.$ext", text)
        FileRetention.prune(dir, listOf(prefix), KEEP)
        return file
    }

    /**
     * Write [text] to [dir]/[name] under the same failure contract as [write] (any failure is an
     * [IOException]) but without retention: the caller owns the name. A failed write removes the
     * partial file so nothing half-written can be shared.
     */
    fun writeNamed(dir: File, name: String, text: String): File =
        writeNamed(dir, name) { it.write(text.toByteArray(Charsets.UTF_8)) }

    /** [writeNamed] for binary content streamed by [body] (e.g. a pcap). */
    fun writeNamed(dir: File, name: String, body: (OutputStream) -> Unit): File {
        val file = File(dir, name)
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
            file.outputStream().use(body)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e as? IOException ?: IOException(e.message ?: "Export failed", e)
        }
    }

    /** App external files dir, or internal files when no external storage is mounted (both FileProvider paths). */
    fun dir(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir
}
