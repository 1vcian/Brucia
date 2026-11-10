package app.brucia

import app.brucia.ir.IrParser
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/** F36: IR and PCAP imports read at most their limit, then fail with a visible "too large". */
class BoundedImportTest {
    private class Endless : InputStream() {
        var served = 0L
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill('a'.code.toByte(), off, off + len); served += len; return len }
    }

    @Test fun exactLimitAcceptedOneMoreRejected() {
        assertEquals(1000, BoundedImport.read(ByteArray(1000).inputStream(), 1000).size)
        val e = assertThrows(ImportTooLargeException::class.java) { BoundedImport.read(ByteArray(1001).inputStream(), 1000) }
        assertEquals(1000L, e.limit)
    }

    @Test fun pcapLimitStopsEndlessProvider() {
        val src = Endless()
        val e = assertThrows(ImportTooLargeException::class.java) {
            BoundedImport.read(src, app.brucia.wifi.PcapDissector.MAX_CAPTURE_BYTES.toLong())
        }
        assertEquals("File too large (max 32 MiB)", e.message)
        assertTrue(src.served <= 32L * 1024 * 1024 + 64 * 1024)
    }

    @Test fun irImportIsBoundedAndParses() {
        val text = "Filetype: IR signals file\n#\nname: Power\ntype: parsed\nprotocol: NEC\naddress: 04 00 00 00\ncommand: 08 00 00 00\n"
        val parsed = BoundedImport.readIr(text.byteInputStream(), parse = IrParser::parse)
        assertEquals(listOf("Power"), parsed.map { it.name })
        val src = Endless()
        var parsedCalled = false
        assertThrows(ImportTooLargeException::class.java) { BoundedImport.readIr(src) { parsedCalled = true } }
        assertFalse(parsedCalled)
        assertTrue(src.served <= BoundedImport.MAX_IR_BYTES + 64 * 1024)
    }

    @Test fun cancellationStopsBetweenReads() {
        var reads = 0
        val src = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return 1 }
        }
        assertThrows(InterruptedException::class.java) { BoundedImport.read(src, 1000) { reads >= 2 } }
        assertEquals(2, reads)
    }
}
