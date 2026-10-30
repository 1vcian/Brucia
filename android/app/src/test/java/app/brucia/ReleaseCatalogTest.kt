package app.brucia

import app.brucia.update.ReleaseCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private fun asset(name: String, tag: String = "v0.2", host: String = "github.com") =
        JSONObject().put("name", name).put("browser_download_url",
            "https://$host/1vcian/BruciaDev/releases/download/$tag/$name")
    private fun release(vararg assets: JSONObject) = JSONObject().put("tag_name", "v0.2")
        .put("body", "Release notes").put("assets", JSONArray(assets.toList())).toString()

    @Test fun rejectsMismatchedPathsPortsAndAmbiguousBoards() {
        val name = "Bruce-lilygo-t-embed-cc1101.bin"
        val base = "https://github.com/1vcian/BruciaDev/releases/download/v0.2/"
        for (url in listOf(base + "Bruce-other.bin", base + "nested/" + name,
            base + name + "?download=1", base + name + "#other",
            base.replace("github.com", "github.com:8443") + name,
            base + "%2e%2e/" + name)) {
            assertTrue(ReleaseCatalog.parse(release(asset(name).put("browser_download_url", url))).boards.isEmpty())
        }
        assertNull(ReleaseCatalog.parse(release(asset("../other.apk"))).apk)
        try { ReleaseCatalog.parse(release(asset("first.apk"), asset("second.apk"))); fail("Ambiguous APK accepted") }
        catch (_: IllegalArgumentException) { }
        val duplicate = release(asset(name), asset("brucia-t-embed-cc1101.bin"))
        try { ReleaseCatalog.parse(duplicate); fail("Ambiguous board accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun selectsOnlyAssetsFromTheSameReleaseAndKeepsBoardsDistinct() {
        val parsed = ReleaseCatalog.parse(release(asset("Brucia-0.2.apk"), asset("brucia-t-embed-cc1101.bin"),
            asset("Bruce-m5stack-cardputer.bin"), asset("Bruce-old.bin", "v0.1"), asset("arbitrary.bin")))
        assertEquals("v0.2", parsed.tag)
        assertEquals("Release notes", parsed.notes)
        assertNotNull(parsed.apk)
        assertEquals(listOf("lilygo-t-embed-cc1101", "m5stack-cardputer"), parsed.boards.map { it.env })
    }

    @Test fun rejectsUntrustedAssetHostsAndMissingFirmwareHasNoFallback() {
        val parsed = ReleaseCatalog.parse(release(asset("Brucia.apk", host = "example.org")))
        assertNull(parsed.apk)
        assertTrue(parsed.boards.isEmpty())
        assertEquals("CYD-2432S028", ReleaseCatalog.parse(release(asset("Bruce-CYD-2432S028.bin"))).boards.single().env)
    }

    @Test fun rejectsDraftPrereleaseAndInvalidVersions() {
        for (json in listOf(JSONObject().put("tag_name", "wordlists"),
            JSONObject().put("tag_name", "v0.2").put("draft", true),
            JSONObject().put("tag_name", "v0.2").put("prerelease", true))) {
            try { ReleaseCatalog.parse(json.toString()); fail("Accepted non-release metadata") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
