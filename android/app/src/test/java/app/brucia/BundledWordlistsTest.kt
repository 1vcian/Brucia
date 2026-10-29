package app.brucia

import app.brucia.wordlist.BundledWordlists
import app.brucia.wordlist.WordlistCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Integrity of the bundled offline list and the downloadable real-wordlist catalog. */
class BundledWordlistsTest {

    @Test fun hasAnOfflineQuickList() {
        assertTrue(BundledWordlists.all.isNotEmpty())
        for (w in BundledWordlists.all) {
            assertTrue(w.asset.endsWith(".txt"))
            assertTrue(w.title.isNotBlank())
            assertTrue(w.description.isNotBlank())
        }
    }

    @Test fun offlineAssetExistsAndIsNonEmpty() {
        val assetsDir = File("src/main/assets")
        if (!assetsDir.isDirectory) return
        for (w in BundledWordlists.all) {
            val f = File(assetsDir, w.asset)
            assertTrue("missing ${w.asset}", f.isFile)
            assertTrue("${w.asset} empty", f.readLines().count { it.isNotBlank() } > 0)
        }
    }

    @Test fun catalogOffersRealWordlistsIncludingRockyou() {
        assertTrue("expected several downloadable wordlists", WordlistCatalog.all.size >= 3)
        assertTrue("rockyou must be offered", WordlistCatalog.all.any { it.name == "rockyou.txt" })
    }

    @Test fun catalogEntriesAreWellFormed() {
        for (e in WordlistCatalog.all) {
            assertTrue("https url", e.url.startsWith("https://"))
            assertTrue(e.name.isNotBlank() && e.title.isNotBlank() && e.description.isNotBlank())
            assertTrue("positive size", e.approxBytes > 0)
        }
    }

    @Test fun catalogNamesAreUnique() {
        val names = WordlistCatalog.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
