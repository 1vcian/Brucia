package app.brucia

import app.brucia.update.ReleaseCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private fun asset(name: String, tag: String = "v0.2", host: String = "github.com") =
        JSONObject().put("name", name).put("browser_download_url",
            BrandLinks.DOWNLOAD_PREFIX.replace("https://github.com/", "https://$host/") + "$tag/$name")
    private fun release(vararg assets: JSONObject) = JSONObject().put("tag_name", "v0.2")
        .put("body", "Release notes").put("assets", JSONArray(assets.toList())).toString()

    @Test fun rejectsMismatchedPathsPortsAndAmbiguousBoards() {
        val name = "Bruce-lilygo-t-embed-cc1101.bin"
        val base = "${BrandLinks.DOWNLOAD_PREFIX}v0.2/"
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

    // Same inputs as tools/test_site_release.cjs: app and website accept exactly one URL spelling.
    @Test fun acceptsOnlyTheExactCanonicalAssetUrl() {
        val name = "Bruce-lilygo-t-embed-cc1101.bin"
        val canonical = "${BrandLinks.DOWNLOAD_PREFIX}v0.2/$name"
        for (url in listOf(canonical.replace("github.com", "GitHub.com"), canonical.replace("https:", "HTTPS:"),
            canonical.replace("github.com", "github.com:443"), "$canonical?", "$canonical#", " $canonical",
            canonical.replace("v0.2/", "v0.2/x/../"), canonical.replace("v0.2/", "v0.2/x/%2e%2e/"))) {
            assertTrue(url, ReleaseCatalog.parse(release(asset(name).put("browser_download_url", url))).boards.isEmpty())
        }
        val variant = asset(name).put("browser_download_url", canonical.replace("github.com", "GITHUB.COM"))
        val boards = ReleaseCatalog.parse(release(asset("brucia-t-embed-cc1101.bin"), variant)).boards
        assertEquals(listOf(asset("brucia-t-embed-cc1101.bin").getString("browser_download_url")), boards.map { it.url })
        assertEquals(canonical, ReleaseCatalog.parse(release(asset(name))).boards.single().url)
    }

    @Test fun rejectsNonStringOrUnboundedVersionsAndDeepNesting() {
        for (tag in listOf<Any>(0.2, JSONArray().put("v0.2"), "v0.1.1234567890")) {
            try { ReleaseCatalog.parse(JSONObject().put("tag_name", tag).toString()); fail("Accepted tag $tag") }
            catch (_: IllegalArgumentException) { }
        }
        // Android's bundled org.json has no depth limit; recursion must not reach it.
        val nested = "{\"tag_name\":\"v0.2\",\"x\":" + "[".repeat(100) + "]".repeat(100) + "}"
        try { ReleaseCatalog.parse(nested); fail("Accepted deeply nested metadata") }
        catch (_: IllegalArgumentException) { }
        val strings = "{\"tag_name\":\"v0.2\",\"body\":\"" + "[{\\\"".repeat(100) + "\"}"
        assertEquals("[{\"".repeat(100), ReleaseCatalog.parse(strings).notes)
        assertEquals("", ReleaseCatalog.parse(JSONObject().put("tag_name", "v0.2").put("body", JSONObject.NULL).toString()).notes)
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
