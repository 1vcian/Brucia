package app.brucia

import app.brucia.files.FileList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListTest {
    @Test fun parsesDirsAndFiles() {
        val out = "BrucePCAP\t<DIR>\r\nhandshake.pcap\t4096\r\nnotes.txt\t12\r\n> storage list /\n\n"
        val e = FileList.parse(out)
        assertEquals(3, e.size)
        // dirs first, then files alphabetically
        assertTrue(e[0].isDir)
        assertEquals("BrucePCAP", e[0].name)
        val pcap = e.first { it.name == "handshake.pcap" }
        assertEquals(false, pcap.isDir)
        assertEquals(4096L, pcap.size)
    }

    @Test fun skipsNonEntryLines() {
        val out = "Invalid status\njust text with no tab\n"
        assertEquals(0, FileList.parse(out).size)
    }

    @Test fun dropsNamesThatCouldEscapeAFolder() {
        val out = "../../shared_prefs/x.xml\t5\nsub/evil\t1\n..\t<DIR>\nok.txt\t2\nbell\u0007\t3\n"
        assertEquals(listOf(app.brucia.files.FileEntry("ok.txt", false, 2)), FileList.parse(out))
    }

    @Test fun parseListingDropsAPartialFinalLine() {
        val l = FileList.parseListing("a.txt\t12\nb.bin\t40")
        assertEquals(listOf(app.brucia.files.FileEntry("a.txt", false, 12)), l.entries)
        assertEquals(false, l.truncated)
        val full = FileList.parseListing("a.txt\t12\n" + "x".repeat(3500))
        assertEquals(true, full.truncated)
        assertEquals(1, full.entries.size)
    }
}
