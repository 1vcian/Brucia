package app.brucia

import app.brucia.nfc.MifareDictionary
import app.brucia.nfc.MifareDictionaryService
import app.brucia.wordlist.WordlistCatalog
import java.io.IOException
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MifareDictionaryTest {
    @Test fun parsesCommunityFormatsAndDeduplicates() {
        val keys = MifareDictionary.read(("\uFEFF# community dictionary\r\n" +
            "ffffffffffff\r\nFFFFFFFFFFFF # duplicate\nA0A1A2A3A4A5\n" +
            "password123\n1234\n12345678901234\n").byteInputStream())
        assertEquals(listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5"), keys)
    }

    @Test fun rejectsEmptyNonKeyAndOversizedImports() {
        for (text in listOf("", "# no keys\npassword123", "<html>not a dictionary</html>")) {
            assertThrows(Exception::class.java) { MifareDictionary.read(text.byteInputStream()) }
        }
        assertThrows(IOException::class.java) {
            MifareDictionary.read(ByteArray(MifareDictionary.MAX_BYTES.toInt() + 1) { 65 }.inputStream())
        }
    }

    @Test fun sourcesAreAdditiveHttpsAndDistinct() {
        assertEquals(setOf("flipper", "proxmark3"), MifareDictionary.sources.map { it.id }.toSet())
        assertTrue(MifareDictionary.sources.all { it.url.startsWith("https://raw.githubusercontent.com/") })
        assertTrue(WordlistCatalog.all.any { it.name == "rockyou.txt" })
        assertTrue(WordlistCatalog.all.any { it.name == "top-100k.txt" })
        assertEquals(2, WordlistCatalog.all.count { it.name.startsWith("probable-wpa-") })
        assertEquals(WordlistCatalog.all.size, WordlistCatalog.all.map { it.name }.distinct().size)
    }

    @Test fun mergeAndFailuresKeepPreviouslyImportedKeys() = runVirtual {
        val service = MifareDictionaryService(this, virtualUnconfined)
        service.load("first") { listOf("FFFFFFFFFFFF") }
        yield()
        service.load("second") { listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5") }
        yield()
        assertEquals(2, service.status.value.keys.size)
        assertTrue(service.status.value.message.contains("1 new keys"))
        service.load("broken") { throw IOException("Interrupted download") }
        yield()
        assertEquals(2, service.status.value.keys.size)
        assertFalse(service.status.value.busy)
        assertEquals("Interrupted download", service.status.value.message)
    }

    @Test fun busyPreventsSecondImportAndClearFencesLateResults() = runVirtual {
        val service = MifareDictionaryService(this, virtualUnconfined)
        var ignored = false
        service.load("late") {
            service.clear() // Simulate a cancellation before blocking IO returns.
            listOf("FFFFFFFFFFFF")
        }
        service.load("ignored") { ignored = true; listOf("A0A1A2A3A4A5") }
        yield()
        assertFalse(ignored)
        assertTrue(service.status.value.keys.isEmpty())
        assertFalse(service.status.value.busy)
        service.load("fresh") { listOf("A0A1A2A3A4A5") }
        yield()
        assertEquals(listOf("A0A1A2A3A4A5"), service.status.value.keys)
    }
}
