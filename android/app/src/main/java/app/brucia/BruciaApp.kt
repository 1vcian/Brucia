package app.brucia

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Application entry point. Installs a last-resort uncaught-exception handler as early as possible so
 * that if the app crashes - including on startup - the full stack trace is written to a file the user
 * can retrieve with any file manager (Android/data/app.brucia/files/last_crash.txt) WITHOUT needing
 * adb/logcat. It then chains to the platform handler so the normal crash dialog still shows.
 */
class BruciaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Start the rolling app log as early as possible so startup failures are captured too.
        BruciaLog.init(this)
        // Update downloads left from an earlier run (installed or abandoned), off the main thread.
        Thread {
            val n = app.brucia.update.AppUpdater.cleanupStaleDownloads(
                listOf(app.brucia.update.AppUpdater.apkDir(this)) + app.brucia.update.AppUpdater.legacyApkDirs(this),
                System.currentTimeMillis(),
            )
            if (n > 0) BruciaLog.i("Update", "removed $n stale update download(s)")
        }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = "Brucia crash\nwhen: ${Date()}\nthread: ${thread.name}\n\n$sw"
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "last_crash.txt").writeText(text)
                // Also fold the crash into the rolling log so an exported log is self-contained.
                BruciaLog.e("Crash", "uncaught on ${thread.name}", error)
                // And drop a copy in the public Download/Brucia folder so the user can grab it easily.
                BruciaLog.saveToDownloads(this, "last_crash.txt", text)
            } catch (_: Throwable) {
                // Never let the crash logger itself hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
