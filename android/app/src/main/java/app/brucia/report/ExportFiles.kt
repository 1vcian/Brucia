package app.brucia.report

import app.brucia.utils.FileRetention
import java.io.File
import java.io.IOException

/**
 * Report / session exports handed to the share sheet. Each export is a new timestamped file; only
 * the newest [KEEP] of a kind stay (they used to pile up in the app folder forever). Failures are
 * thrown as [IOException] for the caller to show, never as a crash.
 */
object ExportFiles {
    const val KEEP = 10

    fun write(dir: File, prefix: String, ext: String, text: String, now: Long = System.currentTimeMillis()): File {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
            val file = File(dir, "$prefix$now.$ext")
            file.writeText(text)
            FileRetention.prune(dir, listOf(prefix), KEEP)
            return file
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Export failed", e)
        }
    }
}
