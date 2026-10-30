package app.brucia.update

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        val release = ReleaseCatalog.parse(httpGet(RELEASES_API))
        return Release(release.tag, release.apk,
            release.boards.firstOrNull { it.env == "lilygo-t-embed-cc1101" }?.url, release.notes)
    }

    /** One selectable per-board firmware image from the releases. */
    data class BoardFw(val label: String, val env: String, val url: String)

    /** Latest GitHub release holding per-board merged firmware bins (Bruce-<env>.bin). */
    const val BOARDS_RELEASE_API = RELEASES_API

    /**
     * List every per-board firmware image (Bruce-<env>.bin) published in the latest firmware release, so the
     * in-app flasher can offer a board picker. Blocking; call off the main thread.
     */
    fun fetchBoards(): List<BoardFw> {
        return ReleaseCatalog.parse(httpGet(BOARDS_RELEASE_API)).boards.map {
            BoardFw(label = it.env.replace('-', ' '), env = it.env, url = it.url)
        }
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

    /** Legacy 0.8.x firmware predates the fork's 0.1 line: capability migration beats numeric ordering. */
    fun firmwareUpdateNeeded(current: String, latest: String, verifiedFiles: Boolean, imageAvailable: Boolean): Boolean =
        imageAvailable && current.isNotBlank() && latest.isNotBlank() && (!verifiedFiles || isNewer(current, latest))

    fun supportsInAppFirmware(board: String): Boolean = board.lowercase().let { "t-embed" in it && "cc1101" in it }

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
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cache")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create update cache")
        val dest = File(dir, "brucia-update.apk")
        val conn = openWithRedirects(URL(url))
        try {
            if (conn.responseCode / 100 != 2) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val part = File.createTempFile("brucia-update-", ".part", dir)
            try {
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        DownloadIO.copy(input, out, total, 128L * 1024 * 1024, isCancelled, onProgress)
                    }
                }
                DownloadIO.promote(part, dest)
                return dest
            } finally { part.delete() }
        } finally { conn.disconnect() }
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
            if (conn.responseCode / 100 != 2) throw IOException(if (conn.responseCode == 404) "Release unavailable: repository is private or no release is published" else "HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                DownloadIO.copy(input, out, total, 16L * 1024 * 1024, isCancelled, onProgress)
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** Whether the user has granted this app permission to install packages. */
    fun canInstall(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    /** Send the user to the one-time "install unknown apps" grant for this app. */
    fun openInstallSettings(ctx: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** Launch the system installer for the downloaded [apk]. */
    fun install(ctx: Context, apk: File) {
        val archive = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException("Downloaded file is not a valid APK")
        if (archive.packageName != ctx.packageName) throw IOException("APK belongs to another application")
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
            if (conn.responseCode / 100 != 2) throw IOException(if (conn.responseCode == 404) "Release unavailable: repository is private or no release is published" else "HTTP ${conn.responseCode}")
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                DownloadIO.copy(input, out, conn.contentLengthLong, 2L * 1024 * 1024, { false }, { _, _ -> })
                out.toString("UTF-8")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openWithRedirects(start: URL): HttpURLConnection = UpdateHttp.follow(start, ::open)

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "Brucia")
        setRequestProperty("Accept", "application/vnd.github+json")
    }
}
