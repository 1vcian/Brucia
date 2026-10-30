package app.brucia

import app.brucia.files.DeviceFilePush
import app.brucia.files.FileKinds
import app.brucia.files.FileTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64 as JBase64

class DeviceFilesTest {

    // --- DeviceFilePush.chunk ---

    @Test fun emptyInputYieldsOneEmptyChunk() {
        val chunks = DeviceFilePush.chunk(ByteArray(0))
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].size)
    }

    @Test fun smallFileIsOneChunk() {
        val chunks = DeviceFilePush.chunk("hello".toByteArray())
        assertEquals(1, chunks.size)
        assertEquals("hello", String(chunks[0]))
    }

    @Test fun largeFileSplitsOnBoundary() {
        val bytes = ByteArray(DeviceFilePush.MAX_RAW_CHUNK * 2 + 10) { (it % 251).toByte() }
        val chunks = DeviceFilePush.chunk(bytes)
        assertEquals(3, chunks.size)
        assertEquals(DeviceFilePush.MAX_RAW_CHUNK, chunks[0].size)
        assertEquals(DeviceFilePush.MAX_RAW_CHUNK, chunks[1].size)
        assertEquals(10, chunks[2].size)
        // Reassembly must equal the original.
        val joined = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        assertTrue(bytes.contentEquals(joined))
    }

    @Test fun customChunkSizeIsRespectedAndCoerced() {
        assertEquals(5, DeviceFilePush.chunk(ByteArray(10), 2).size)
        // A zero/negative size must not loop forever; it is coerced to 1.
        assertEquals(4, DeviceFilePush.chunk(ByteArray(4), 0).size)
        assertEquals(1, DeviceFilePush.chunk(ByteArray(10), Int.MAX_VALUE).size)
    }

    // --- DeviceFilePush.command ---

    @Test fun firstChunkTruncatesRestAppend() {
        assertEquals("fs_write /a.txt QUJD", DeviceFilePush.command("/a.txt", 0, "QUJD"))
        assertEquals("fs_write_append /a.txt QUJD", DeviceFilePush.command("/a.txt", 1, "QUJD"))
        assertEquals("fs_write_append /a.txt QUJD", DeviceFilePush.command("/a.txt", 7, "QUJD"))
        assertEquals("fs_write /path with spaces/a.txt ", DeviceFilePush.command("/path with spaces/a.txt", 0, ""))
    }

    @Test fun rejectsPathsThatWouldBeChangedByTheWireGrammar() {
        for (path in listOf("", " /a", "/a ", "/a\n", "/a\u0000b")) {
            try { DeviceFilePush.command(path, 0, ""); org.junit.Assert.fail("Accepted invalid path") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun base64RoundTripOverChunks() {
        // Simulate what the ViewModel sends, decode each chunk's base64, and confirm the file rebuilds.
        val original = ByteArray(DeviceFilePush.MAX_RAW_CHUNK + 123) { (it * 7 % 256).toByte() }
        val chunks = DeviceFilePush.chunk(original)
        var rebuilt = ByteArray(0)
        for ((i, c) in chunks.withIndex()) {
            val b64 = JBase64.getEncoder().encodeToString(c)
            val cmd = DeviceFilePush.command("/f.bin", i, b64)
            val argB64 = cmd.substringAfterLast(' ')
            rebuilt += JBase64.getDecoder().decode(argB64)
        }
        assertTrue(original.contentEquals(rebuilt))
    }

    // --- FileKinds ---

    @Test fun classifiesByExtension() {
        assertTrue(FileKinds.isEditable("a.js"))
        assertTrue(FileKinds.isEditable("payload.ducky"))
        assertTrue(FileKinds.isEditable("page.HTML"))
        assertFalse(FileKinds.isEditable("capture.pcap"))
        assertTrue(FileKinds.isHtml("x.htm"))
        assertFalse(FileKinds.isHtml("x.js"))
        assertTrue(FileKinds.isPcap("capture.CAP"))
        assertFalse(FileKinds.isPcap("notes.txt"))
    }

    @Test fun mimeMapping() {
        assertEquals("application/vnd.tcpdump.pcap", FileKinds.mime("c.pcap"))
        assertEquals("text/html", FileKinds.mime("p.html"))
        assertEquals("text/javascript", FileKinds.mime("s.js"))
        assertEquals("application/json", FileKinds.mime("d.json"))
        assertEquals("application/octet-stream", FileKinds.mime("blob.weird"))
        assertEquals("text/plain", FileKinds.mime("sig.sub"))
    }

    // --- FileTemplates ---

    @Test fun templatesAreRegistered() {
        assertTrue(FileTemplates.all.size >= 5)
        assertTrue(FileTemplates.all.all { it.asset.startsWith(FileTemplates.DIR + "/") })
        assertTrue(FileTemplates.all.any { it.fileName.endsWith(".js") })
        assertTrue(FileTemplates.all.any { it.fileName.endsWith(".ducky") })
        assertTrue(FileTemplates.all.any { it.fileName.endsWith(".sub") })
        assertTrue(FileTemplates.all.any { it.fileName.endsWith(".html") })
        assertEquals("script.js", FileTemplates.byFileName("script.js")?.fileName)
    }
}
