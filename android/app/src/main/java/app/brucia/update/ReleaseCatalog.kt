package app.brucia.update

import org.json.JSONObject
import java.net.URI

/** Parse a single release snapshot; board selection never falls back to another release. */
object ReleaseCatalog {
    data class Board(val env: String, val url: String)
    data class Release(val tag: String, val notes: String, val apk: String?, val boards: List<Board>)

    fun parse(body: String): Release {
        val json = JSONObject(body)
        require((!json.has("draft") || json.opt("draft") == false) &&
            (!json.has("prerelease") || json.opt("prerelease") == false)) { "Release is not stable" }
        val tag = json.optString("tag_name")
        require(Regex("[vV]?[0-9]+(?:\\.[0-9]+){1,2}").matches(tag)) { "Invalid release version" }
        val assets = json.optJSONArray("assets")
        var apk: String? = null
        val boards = linkedMapOf<String, Board>()
        if (assets != null) for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(name)) continue
            val url = asset.optString("browser_download_url")
            val uri = try { URI(url) } catch (_: Exception) { continue }
            if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
                uri.port !in listOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null ||
                uri.rawPath != "/1vcian/BruciaDev/releases/download/$tag/$name") continue
            if (name.endsWith(".apk", true)) {
                require(apk == null) { "Ambiguous APK assets" }
                apk = url
            }
            if (!name.endsWith(".bin")) continue
            val rawEnv = when {
                name.startsWith("Bruce-") -> name.removePrefix("Bruce-")
                name.startsWith("brucia-") -> name.removePrefix("brucia-")
                else -> continue
            }.removeSuffix(".bin")
            val env = if (rawEnv == "t-embed-cc1101") "lilygo-t-embed-cc1101" else rawEnv
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]*").matches(env)) continue
            require(!boards.containsKey(env)) { "Ambiguous firmware assets for $env" }
            boards[env] = Board(env, url)
        }
        return Release(tag, json.optString("body"), apk, boards.values.sortedBy { it.env })
    }
}
