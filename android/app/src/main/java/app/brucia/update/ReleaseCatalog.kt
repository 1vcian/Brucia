package app.brucia.update

import org.json.JSONObject

/**
 * Parse a single release snapshot; board selection never falls back to another release.
 * Mirrors docs/release-catalog.js: same tag rule, exact canonical asset URL string, same aliases,
 * same size/digest rules (see [AssetIntegrity]). An asset whose size or digest is present but
 * malformed is not offered; it still counts for the ambiguity checks.
 */
object ReleaseCatalog {
    data class Board(val env: String, val url: String, val integrity: AssetIntegrity = AssetIntegrity.NONE) {
        val label: String get() = env.replace('-', ' ')
    }
    data class Release(val tag: String, val notes: String, val apk: String?, val boards: List<Board>,
                       val apkIntegrity: AssetIntegrity = AssetIntegrity.NONE)

    /** Download bounds; also the largest `size` accepted from metadata. */
    const val APK_MAX_BYTES = 128L * 1024 * 1024
    const val FIRMWARE_MAX_BYTES = 16L * 1024 * 1024

    /** GitHub release metadata nests about four levels; Android's org.json recursion is unbounded. */
    private const val MAX_DEPTH = 32
    private const val DOWNLOAD_PREFIX = "https://github.com/1vcian/BruciaDev/releases/download/"

    fun parse(body: String): Release {
        requireBoundedNesting(body)
        val json = JSONObject(body)
        require((!json.has("draft") || json.opt("draft") == false) &&
            (!json.has("prerelease") || json.opt("prerelease") == false)) { "Release is not stable" }
        val tag = json.opt("tag_name") as? String ?: ""
        require(Regex("[vV]?[0-9]{1,9}(?:\\.[0-9]{1,9}){1,2}").matches(tag)) { "Invalid release version" }
        val assets = json.optJSONArray("assets")
        var apk: String? = null
        var apkIntegrity = AssetIntegrity.NONE
        var apkSeen = false
        val boards = linkedMapOf<String, Board>()
        val seenEnvs = hashSetOf<String>()
        if (assets != null) for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.opt("name") as? String ?: continue
            if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(name)) continue
            // No URL normalization: only the exact spelling GitHub publishes is accepted.
            val url = asset.opt("browser_download_url") as? String ?: continue
            if (url != "$DOWNLOAD_PREFIX$tag/$name") continue
            if (name.endsWith(".apk", true)) {
                require(!apkSeen) { "Ambiguous APK assets" }
                apkSeen = true
                integrityOf(asset, APK_MAX_BYTES)?.let { apk = url; apkIntegrity = it }
            }
            if (!name.endsWith(".bin")) continue
            val rawEnv = when {
                name.startsWith("Bruce-") -> name.removePrefix("Bruce-")
                name.startsWith("brucia-") -> name.removePrefix("brucia-")
                else -> continue
            }.removeSuffix(".bin")
            val env = if (rawEnv == "t-embed-cc1101") "lilygo-t-embed-cc1101" else rawEnv
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]*").matches(env)) continue
            require(seenEnvs.add(env)) { "Ambiguous firmware assets for $env" }
            integrityOf(asset, FIRMWARE_MAX_BYTES)?.let { boards[env] = Board(env, url, it) }
        }
        return Release(tag, json.opt("body") as? String ?: "", apk,
            java.util.Collections.unmodifiableList(boards.values.sortedBy { it.env }), apkIntegrity)
    }

    /** JSON null and a missing key are both "absent". */
    private fun integrityOf(asset: JSONObject, limit: Long): AssetIntegrity? = AssetIntegrity.parse(
        if (asset.isNull("size")) null else asset.opt("size"),
        if (asset.isNull("digest")) null else asset.opt("digest"), limit)

    private fun requireBoundedNesting(body: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in body) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> require(++depth <= MAX_DEPTH) { "Release metadata nested too deeply" }
                '}', ']' -> depth--
            }
        }
    }
}
