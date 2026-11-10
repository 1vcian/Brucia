package app.brucia

import app.brucia.update.AppUpdater
import app.brucia.update.ReleaseCatalog
import app.brucia.wordlist.WordlistCatalog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** App dictionary downloads are hosted by Brucia and follow brand.json. */
class BrandLinksTest {
    private val brand: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "brand.json").isFile) dir = dir.parentFile
        JSONObject(File(requireNotNull(dir) { "brand.json not found above the test directory" }, "brand.json").readText())
    }
    private val owner get() = brand.getString("github_owner")
    private val repo get() = brand.getString("github_repo")

    @Test fun linksAreDerivedFromBrandJson() {
        assertEquals(brand.getString("name"), BrandLinks.NAME)
        assertEquals("https://github.com/$owner/$repo", BrandLinks.REPO_URL)
        assertEquals("github.com/$owner/$repo", BrandLinks.REPO_DISPLAY)
        assertEquals("https://github.com/$owner/$repo/releases/download/", BrandLinks.DOWNLOAD_PREFIX)
        assertEquals("https://api.github.com/repos/$owner/$repo/releases/latest", BrandLinks.RELEASES_API)
        assertEquals(brand.getString("site_url"), BrandLinks.SITE_URL)
        assertEquals(brand.getString("site_url") + "wiki/", BrandLinks.WIKI_URL)
        assertFalse(BrandLinks.SITE_DISPLAY.startsWith("https://") || BrandLinks.SITE_DISPLAY.endsWith("/"))
    }

    @Test fun updaterAndWordlistsUseTheCentralConfig() {
        assertEquals(BrandLinks.RELEASES_API, AppUpdater.RELEASES_API)
        assertTrue(WordlistCatalog.all.isNotEmpty())
        for (entry in WordlistCatalog.all) {
            assertEquals("${BrandLinks.DOWNLOAD_PREFIX}wordlists/${entry.name}", entry.url)
        }
    }

    @Test fun updaterAcceptsCurrentAndLaunchRepositoryNamesOnly() {
        val accepted = brand.getJSONArray("accepted_release_repos").let { a -> (0 until a.length()).map { a.getString(it) } }
        val launchRepo = brand.getJSONObject("launch").getString("github_repo")
        assertTrue(accepted.contains(repo) && accepted.contains(launchRepo))
        assertEquals(accepted.toSet() + repo, BrandLinks.ACCEPTED_DOWNLOAD_PREFIXES
            .map { it.removePrefix("https://github.com/$owner/").removeSuffix("/releases/download/") }.toSet())
        fun boards(prefix: String) = ReleaseCatalog.parse(JSONObject().put("tag_name", "v0.2").put("assets", JSONArray(listOf(
            JSONObject().put("name", "brucia-t-embed-cc1101.bin")
                .put("browser_download_url", "${prefix}v0.2/brucia-t-embed-cc1101.bin")))).toString()).boards
        for (name in accepted) {
            assertEquals(name, 1, boards("https://github.com/$owner/$name/releases/download/").size)
        }
        assertTrue(boards("https://github.com/$owner/SomeOtherRepo/releases/download/").isEmpty())
        assertTrue(boards(brand.getJSONObject("upstream").getString("repo_url") + "/releases/download/").isEmpty())
    }
}
