package app.brucia

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central app log. Everything goes to logcat AND to a rolling on-disk file, so a user on any Android
 * ROM (LineageOS, GrapheneOS, a vendor skin, ...) can export it from the Console tab and attach it to
 * a GitHub issue without needing adb/logcat. This is what makes "the app stops logging APs after 5
 * minutes with the screen off" debuggable: the WakeLock / foreground-start / transport failures that
 * were silently swallowed now leave a timestamped trace here.
 *
 * All writes are synchronized and fully guarded: logging must never throw or crash the app. The file
 * is size-capped; on overflow the newest half is kept.
 */
object BruciaLog {
    private const val TAG = "Brucia"
    private const val MAX_BYTES = 256 * 1024  // cap the log file at ~256 KB
    private const val KEEP_BYTES = 128 * 1024 // keep the newest ~128 KB when it overflows
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    @Volatile private var file: File? = null

    /** Point the log at the app's external files dir. Safe to call more than once. */
    fun init(ctx: Context) {
        synchronized(lock) {
            if (file != null) return
            file = try { File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "brucia.log") } catch (_: Throwable) { null }
        }
        i("Log", "---- log start ${Date()} (app ${BuildConfig.VERSION_NAME}) ----")
    }

    fun d(tag: String, msg: String) { try { Log.d(prefix(tag), msg) } catch (_: Exception) { }; append("D", tag, msg, null) }
    fun i(tag: String, msg: String) { try { Log.i(prefix(tag), msg) } catch (_: Exception) { }; append("I", tag, msg, null) }
    fun w(tag: String, msg: String, e: Throwable? = null) { try { Log.w(prefix(tag), msg, e) } catch (_: Exception) { }; append("W", tag, msg, e) }
    fun e(tag: String, msg: String, e: Throwable? = null) { try { Log.e(prefix(tag), msg, e) } catch (_: Exception) { }; append("E", tag, msg, e) }

    private fun prefix(tag: String) = if (tag.startsWith(TAG)) tag else "$TAG/$tag"

    private fun append(level: String, tag: String, msg: String, err: Throwable?) {
        val f = file ?: return
        try {
            val sb = StringBuilder()
            val timestamp = synchronized(lock) { fmt.format(Date()) }
            sb.append(timestamp).append(' ').append(level).append(' ')
                .append(tag).append(": ").append(msg).append('\n')
            if (err != null) {
                val sw = StringWriter(); err.printStackTrace(PrintWriter(sw)); sb.append(sw)
            }
            synchronized(lock) {
                f.appendText(sb.toString())
                if (f.length() > MAX_BYTES) rotate(f)
            }
        } catch (_: Throwable) {
            // Logging must never crash the app - drop this line silently.
        }
    }

    private fun rotate(f: File) {
        try {
            val bytes = f.readBytes()
            val keep = if (bytes.size > KEEP_BYTES) bytes.copyOfRange(bytes.size - KEEP_BYTES, bytes.size) else bytes
            f.writeBytes(keep)
        } catch (_: Throwable) {}
    }

    /** The last [maxLines] lines of the log, for export. */
    fun tail(maxLines: Int = 2000): String {
        val f = file ?: return "(log not initialised)"
        return try {
            val lines = synchronized(lock) { f.readLines() }
            (if (lines.size <= maxLines) lines else lines.subList(lines.size - maxLines, lines.size))
                .joinToString("\n")
        } catch (e: Throwable) {
            "(could not read log: ${e.message})"
        }
    }

    /**
     * Write the last [maxLines] of the log to a shareable text file and return it, or null on failure.
     * The file lives in the external files dir, which the app's FileProvider already exposes.
     */
    fun exportTail(ctx: Context, maxLines: Int = 2000): File? {
        return try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val out = File(dir, "brucia-log.txt")
            out.writeText(tail(maxLines))
            out
        } catch (e: Throwable) {
            w("Log", "export failed", e)
            null
        }
    }

    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * Save [text] as [name] into the public **Download/Brucia** folder so the user can find it in the
     * phone's Downloads (no file-manager spelunking into Android/data). Uses MediaStore on Android 10+
     * (no permission needed), the legacy public dir below that. Returns a user-facing path or null.
     */
    fun saveToDownloads(ctx: Context, name: String, text: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Brucia")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "Download/Brucia/$name"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Brucia")
                dir.mkdirs()
                File(dir, name).writeText(text)
                "Download/Brucia/$name"
            }
        } catch (e: Throwable) {
            w("Log", "saveToDownloads failed", e)
            null
        }
    }

    /** Save the last [maxLines] of the log to Download/Brucia with a timestamped name; returns the path. */
    fun exportToDownloads(ctx: Context, maxLines: Int = 2000): String? =
        saveToDownloads(ctx, "brucia-log-${fileStamp.format(Date())}.txt", tail(maxLines))
}
