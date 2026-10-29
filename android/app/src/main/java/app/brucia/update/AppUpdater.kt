package app.brucia.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app OTA self-update: reads the latest GitHub release of the project, compares it
 * to the installed version, and (when newer) downloads the APK and hands it to the
 * system package installer via the app's FileProvider.
 *
 * Networking reuses the plain HttpURLConnection + manual-redirect style already used
 * by WordlistStore (GitHub release asset URLs redirect to a CDN).
 */
object AppUpdater {

    const val RELEASES_API = "https://api.github.com/repos/1vcian/BruciaDev/releases/latest"

    /** Fallback merged firmware if no matching asset is attached to the release. */
    const val FIRMWARE_FALLBACK_URL = "https://1vcian.me/BruciaDev/firmware/brucia-t-embed-cc1101.bin"

    private const val FILEPROVIDER_AUTHORITY = "app.brucia.fileprovider"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** The pieces of the latest release the app cares about. */
    data class Release(
        val tag: String,
        val apkUrl: String?,
        val firmwareUrl: String?,
        val notes: String, // the GitHub release body (Markdown): what changed, shown in-app
    )

    /** Result of comparing the installed app version to the latest release. */
    data class AppUpdate(
        val current: String,
        val latest: String,
        val apkUrl: String?,
        val newer: Boolean,
    )

    /**
     * Query the GitHub Releases API for the latest release. Blocking; call off the
     * main thread. Throws [IOException] on network / parse failure.
     */
    fun fetchLatest(): Release {
        val body = httpGet(RELEASES_API)
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifEmpty { json.optString("name") }
        val notes = json.optString("body")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var firmwareUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name").lowercase()
                val url = a.optString("browser_download_url")
                if (url.isEmpty()) continue
                if (apkUrl == null && name.endsWith(".apk")) apkUrl = url
                if (firmwareUrl == null && name.endsWith(".bin") && name.contains("t-embed-cc1101")) {
                    firmwareUrl = url
                }
            }
        }
        return Release(tag = tag, apkUrl = apkUrl, firmwareUrl = firmwareUrl, notes = notes)
    }

    /** One selectable per-board firmware image from the releases. */
    data class BoardFw(val label: String, val env: String, val url: String)

    /** GitHub release that holds the per-board merged firmware bins (Bruce-<env>.bin). */
    const val BOARDS_RELEASE_API = "https://api.github.com/repos/1vcian/BruciaDev/releases/tags/wordlists"

    /**
     * List every per-board firmware image (Bruce-<env>.bin) published in the boards release, so the
     * in-app flasher can offer a board picker. Blocking; call off the main thread.
     */
    fun fetchBoards(): List<BoardFw> {
        val out = ArrayList<BoardFw>()
        try {
            val json = JSONObject(httpGet(BOARDS_RELEASE_API))
            val assets = json.optJSONArray("assets") ?: return out
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (url.isEmpty() || !name.endsWith(".bin")) continue
                // Bruce-<env>.bin -> env
                val env = name.removePrefix("Bruce-").removePrefix("brucia-").removeSuffix(".bin")
                if (env.isBlank()) continue
                out.add(BoardFw(label = env.replace('-', ' '), env = env, url = url))
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("Updater", "parse firmware manifest failed", e) }
        return out.sortedBy { it.label }
    }

    /** Compare [current] against the latest release. */
    fun check(current: String): AppUpdate {
        val release = fetchLatest()
        return AppUpdate(
            current = current,
            latest = release.tag,
            apkUrl = release.apkUrl,
            newer = isNewer(current, release.tag),
        )
    }

    /**
     * True if [latest] is a strictly newer version than [current]. A leading "v" is
     * stripped and dotted numeric components are compared left to right.
     */
    fun isNewer(current: String, latest: String): Boolean {
        val c = normalize(current)
        val l = normalize(latest)
        if (l.isEmpty()) return false
        if (l == c) return false
        val cp = c.split('.')
        val lp = l.split('.')
        val n = maxOf(cp.size, lp.size)
        for (i in 0 until n) {
            val cn = cp.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val ln = lp.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (ln != cn) return ln > cn
        }
        return false
    }

    private fun normalize(v: String): String =
        v.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * Stream the APK to the external files cache (covered by the FileProvider paths),
     * following redirects. Returns the downloaded file.
     */
    fun download(
        ctx: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): File {
        val dir = File(ctx.getExternalFilesDir(null), "cache").apply { mkdirs() }
        val dest = File(dir, "brucia-update.apk")
        val part = File(dir, "brucia-update.apk.part")
        val conn = openWithRedirects(URL(url))
        if (conn.responseCode / 100 != 2) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode}")
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
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (done - lastReport > 256 * 1024) { onProgress(done, total); lastReport = done }
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
        return dest
    }

    /**
     * Download a (small, a few MB) binary fully into memory, following redirects.
     * Used for the merged firmware image, which [EspFlasher] flashes from a ByteArray.
     */
    fun downloadBytes(
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): ByteArray {
        val conn = openWithRedirects(URL(url))
        try {
            if (conn.responseCode / 100 != 2) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    if (isCancelled()) throw InterruptedException("cancelled")
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    if (done - lastReport > 256 * 1024) { onProgress(done, total); lastReport = done }
                }
                onProgress(done, total)
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** Whether the user has granted this app permission to install packages. */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.packageManager.canRequestPackageInstalls() else true

    /** Send the user to the one-time "install unknown apps" grant for this app. */
    fun openInstallSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** Launch the system installer for the downloaded [apk]. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, FILEPROVIDER_AUTHORITY, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    // --- HTTP helpers (mirrors WordlistStore) --------------------------------

    private fun httpGet(url: String): String {
        val conn = openWithRedirects(URL(url))
        try {
            if (conn.responseCode / 100 != 2) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openWithRedirects(start: URL): HttpURLConnection {
        var current = start
        var conn = open(current)
        var redirects = 0
        while (conn.responseCode in 301..308 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            current = URL(current, loc)
            conn = open(current)
            redirects++
        }
        return conn
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "Brucia")
        setRequestProperty("Accept", "application/vnd.github+json")
    }
}
