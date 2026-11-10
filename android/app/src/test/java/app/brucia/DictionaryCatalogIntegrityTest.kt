package app.brucia

import app.brucia.nfc.MifareDictionary
import app.brucia.wordlist.WordlistCatalog
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * F42: the size/SHA-256 pins the app enforces and docs/dictionary-sources.json (the record
 * of the Brucia-hosted snapshots) must not drift apart.
 */
class DictionaryCatalogIntegrityTest {
    private data class Pinned(val name: String, val url: String, val size: Long?, val sha256: String?)

    private val manifest: Map<String, JSONObject> by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "docs/dictionary-sources.json").isFile) dir = dir.parentFile
        val json = JSONObject(File(requireNotNull(dir) { "docs/dictionary-sources.json not found" },
            "docs/dictionary-sources.json").readText())
        val files = json.getJSONArray("files")
        (0 until files.length()).map { files.getJSONObject(it) }
            .filterNot { it.getString("name").endsWith("LICENSE.txt") }
            .associateBy { it.getString("name") }
    }

    private val app: List<Pinned> =
        WordlistCatalog.all.map { Pinned(it.name, it.url, it.integrity?.size, it.integrity?.sha256) } +
            MifareDictionary.sources.map {
                Pinned(it.url.substringAfterLast('/'), it.url, it.integrity?.size, it.integrity?.sha256)
            }

    @Test fun everyHostedSnapshotIsPinnedWithTheRecordedSizeAndDigest() {
        assertEquals(4, manifest.size)
        for ((name, entry) in manifest) {
            val pinned = app.singleOrNull { it.name == name } ?: error("$name is in the manifest but not in the app")
            assertEquals("${BrandLinks.DOWNLOAD_PREFIX}wordlists/$name", pinned.url)
            assertEquals(name, entry.getLong("bytes"), pinned.size)
            assertEquals(name, entry.getString("sha256"), pinned.sha256)
        }
    }

    @Test fun pinsExistOnlyForRecordedSnapshotsAndUnpinnedListsAreExplicit() {
        for (entry in app) {
            assertTrue(entry.name, entry.url.startsWith("${BrandLinks.DOWNLOAD_PREFIX}wordlists/"))
            assertEquals(entry.name, entry.url.substringAfterLast('/'))
            if (entry.sha256 != null) assertTrue("${entry.name} pinned without a manifest record", entry.name in manifest)
            assertEquals(entry.name, entry.size == null, entry.sha256 == null)
        }
        // Older release assets with no recorded digest in the repository: deliberately unpinned.
        assertEquals(setOf("rockyou.txt", "top-1M.txt", "top-100k.txt", "top-10k.txt"),
            app.filter { it.sha256 == null }.map { it.name }.toSet())
        for (list in WordlistCatalog.all) list.integrity?.size?.let { assertEquals(list.name, it, list.approxBytes) }
    }
}
