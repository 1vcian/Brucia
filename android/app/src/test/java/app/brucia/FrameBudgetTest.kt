package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.FrameBudget
import app.brucia.transport.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The frame-size bounds the app uses to stay inside the device RX buffer, checked against the
 * bytes [CompanionClient] really writes. An underestimate means a frame silently lost on the wire.
 */
class FrameBudgetTest {

    private class Capture : Transport {
        val frames = mutableListOf<ByteArray>()
        override val isOpen = true
        override fun write(bytes: ByteArray) { frames += bytes }
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() {}
    }

    private fun wirePayload(command: String): Int {
        val t = Capture()
        CompanionClient(t).cmd(command, id = Int.MIN_VALUE)
        return t.frames.single().size - 4 // minus the length prefix
    }

    @Test fun commandBoundIsExactForAsciiAndNeverBelowTheWireForOtherText() {
        assertEquals("ASCII bound is exact with the longest id", wirePayload("fs_ls /sd/BrucePCAP"), FrameBudget.cmdPayload("fs_ls /sd/BrucePCAP"))
        // Two- and three-byte UTF-8, a supplementary (surrogate pair) character, quotes, backslash,
        // slash and control characters: the bound may exceed the wire size but never fall below it.
        for (text in listOf("fs_ls /sd/caf\u00e9 \u00e8\u00e0", "ssid \u4e2d\u6587\u7f51", "note \uD834\uDD1E",
                "say \"hi\" C:\\path", "a/b/c/d", "tab\there\nnew\u0001ctl")) {
            val bound = FrameBudget.cmdPayload(text)
            val wire = wirePayload(text)
            assertTrue("bound $bound < wire $wire for '$text'", bound >= wire)
        }
    }

    @Test fun escapedLengthCountsUtf8Bytes() {
        assertEquals(1, FrameBudget.escapedLength("a"))
        assertEquals(2, FrameBudget.escapedLength("\u00e9"))
        assertEquals(3, FrameBudget.escapedLength("\u4e2d"))
        assertEquals(6, FrameBudget.escapedLength("\uD834\uDD1E")) // 4 on the wire; surrogates count 3 each
        assertEquals(2, FrameBudget.escapedLength("\""))
        assertEquals(6, FrameBudget.escapedLength("\u0001"))
    }

    @Test fun uploadChunksFillTheBudgetAndFitIt() {
        val enc: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
        val bytes = ByteArray(10_000) { it.toByte() }
        val token = "0123456789abcdef0123456789abcdef"
        for (budget in listOf(FrameBudget.LEGACY_RX_MAX, 1024, 4096, FrameBudget.MAX_FRAME)) {
            var offset = 0
            while (offset < bytes.size) {
                val end = FrameBudget.uploadChunkEnd(bytes, offset, budget, token, enc)
                assertTrue(end > offset)
                val cmd = "fs_upload_chunk $token $offset ${enc(bytes.copyOfRange(offset, end))}"
                assertTrue("chunk frame fits budget $budget", wirePayload(cmd) <= budget)
                assertTrue(end - offset <= FrameBudget.UPLOAD_MAX_CHUNK)
                // Not wastefully small: either the last chunk, the per-chunk cap, or 3 more bytes would overflow.
                if (end < bytes.size && end - offset < FrameBudget.UPLOAD_MAX_CHUNK) {
                    val more = "fs_upload_chunk $token $offset ${enc(bytes.copyOfRange(offset, minOf(bytes.size, end + 3)))}"
                    assertTrue(FrameBudget.cmdPayload(more) > budget)
                }
                offset = end
            }
        }
        // A budget below the command prefix cannot carry any data: the caller reports it.
        assertEquals(0, FrameBudget.uploadChunkEnd(bytes, 0, 60, token, enc))
        // A misbehaving encoder that pads output still yields a fitting chunk (defensive path).
        val padded: (ByteArray) -> String = { enc(it) + "=".repeat(40) }
        val end = FrameBudget.uploadChunkEnd(bytes, 0, 1024, token, padded)
        assertTrue(end > 0)
        assertTrue(FrameBudget.cmdPayload("fs_upload_chunk $token 0 ${padded(bytes.copyOfRange(0, end))}") <= 1024)
    }

    @Test fun reportedRxMaxAndPcmFrameSizing() {
        assertEquals(2048, FrameBudget.reported(2048))
        assertEquals(null, FrameBudget.reported(0))
        assertEquals(null, FrameBudget.reported(FrameBudget.MAX_FRAME + 1))
        assertEquals(null, FrameBudget.reported("2048"))
        assertEquals(FrameBudget.LEGACY_RX_MAX, FrameBudget.chunkBudget(null))
        assertEquals(FrameBudget.MAX_FRAME, FrameBudget.sendLimit(null))
        assertEquals(256, FrameBudget.pcmFrameSamples(256, null, 40))
        assertEquals(256, FrameBudget.pcmFrameSamples(256, FrameBudget.MAX_FRAME, 40))
        assertEquals(1, FrameBudget.pcmFrameSamples(256, 50, 40)) // never zero samples
    }
}
