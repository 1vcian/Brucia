package app.brucia

import app.brucia.ble.NusLineAssembler
import app.brucia.ble.NusProtocol
import org.junit.Assert.*
import org.junit.Test

/** BLE console wire helpers; the GATT side (BleConsole) needs a phone and a paired device. */
class NusProtocolTest {
    @Test fun commandLineIsOneTerminatedLineWithinTheFirmwareBound() {
        assertEquals("info\n", NusProtocol.commandLine("  info \r\n"))
        assertNull(NusProtocol.commandLine("   "))
        assertNull(NusProtocol.commandLine("info\nreboot"))
        assertNull(NusProtocol.commandLine("a\rb"))
        assertNotNull(NusProtocol.commandLine("x".repeat(NusProtocol.MAX_LINE_BYTES)))
        assertNull(NusProtocol.commandLine("x".repeat(NusProtocol.MAX_LINE_BYTES + 1)))
        assertNull(NusProtocol.commandLine("é".repeat(NusProtocol.MAX_LINE_BYTES / 2 + 1))) // UTF-8 bytes
        assertEquals("Nothing to send.", NusProtocol.rejection(""))
        assertEquals("One command per line.", NusProtocol.rejection("a\nb"))
        assertNull(NusProtocol.rejection("free"))
    }

    @Test fun chunksFitTheMtuAndRejoinToTheLine() {
        val line = NusProtocol.commandLine("subghz tx 445533 433920000 174 10".repeat(5).take(150))!!
        for (mtu in listOf(23, 0, 64, 247, 517)) {
            val parts = NusProtocol.chunks(line, mtu)
            val max = maxOf(NusProtocol.MIN_CHUNK, mtu - 3)
            assertTrue(parts.all { it.size in 1..max })
            assertEquals(line, parts.fold(ByteArray(0)) { a, b -> a + b }.toString(Charsets.UTF_8))
        }
    }

    @Test fun assemblerJoinsChunksIntoLinesAcrossUtf8Splits() {
        val a = NusLineAssembler()
        val text = "free heap: 123\r\nnome: città\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = ArrayList<String>()
        for (i in bytes.indices step 5) out += a.feed(bytes.copyOfRange(i, minOf(i + 5, bytes.size)))
        assertEquals(listOf("free heap: 123", "nome: città"), out)
        assertEquals("", a.partial())
        assertTrue(a.feed("prompt> ".toByteArray()).isEmpty())
        assertEquals("prompt> ", a.partial())
    }

    @Test fun aLongRunWithoutNewlineIsFlushedNotHeld() {
        val a = NusLineAssembler(maxPending = 16)
        val out = a.feed(ByteArray(40) { 'x'.code.toByte() })
        assertEquals(listOf("x".repeat(16), "x".repeat(16)), out)
        assertEquals("x".repeat(8), a.partial())
    }
}
