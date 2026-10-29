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
}
