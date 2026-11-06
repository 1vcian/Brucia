package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.transport.Transport
import org.junit.Assert.*
import org.junit.Test

/**
 * The decoder must recognise that the device left companion mode (its serial CLI echoes "COMMAND:")
 * even when a TRUNCATED frame - a valid header with an incomplete payload - precedes the echo, since
 * the parked frame would otherwise swallow the echo bytes as "payload". It must also handle a banner
 * split across reads or preceded by noise, recover a never-completing truncated frame within a bounded
 * time (stall), and NOT false-alarm when "COMMAND:" appears inside a valid JSON payload.
 */
class CompanionClientCliTest {
    private class FeedTransport : Transport {
        override var isOpen = true
        private val inbound = ArrayDeque<Byte>()
        override fun write(bytes: ByteArray) {}
        fun feed(bytes: ByteArray) { for (b in bytes) inbound.addLast(b) }
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            var i = 0
            while (i < buf.size && inbound.isNotEmpty()) buf[i++] = inbound.removeFirst()
            return i
        }
        override fun close() { isOpen = false }
    }

    private var clock = 1000L
    private val link = FeedTransport()
    private val client = CompanionClient(link, nowMs = { clock })

    /** uint32-LE length header for a declared payload of [len] bytes. */
    private fun header(len: Int) = byteArrayOf(
        (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
        ((len shr 16) and 0xFF).toByte(), ((len shr 24) and 0xFF).toByte(),
    )

    private fun frame(payload: String): ByteArray {
        val p = payload.toByteArray(Charsets.UTF_8)
        return header(p.size) + p
    }

    @Test fun truncatedFrameThenCliEchoIsDetected() {
        // Valid header (40-byte payload promised) + only part of the payload: the frame parks.
        link.feed(header(40) + "{\"v\":0,\"t".toByteArray(Charsets.US_ASCII))
        assertNull(client.readMessage(20))
        assertFalse("parked frame alone is not the CLI", client.takeCliLeftCompanion())

        // The device is now at the CLI and echoes our next frame as "COMMAND: ...". Those bytes
        // complete the 40-byte "payload", which then fails to parse -> scanned -> banner found.
        link.feed("COMMAND: {\"v\":0,\"t\":9} unknown command\r\n".toByteArray(Charsets.US_ASCII))
        assertNull(client.readMessage(20))
        assertTrue("CLI echo after a truncated frame must be detected", client.takeCliLeftCompanion())
    }

    @Test fun neverCompletingTruncatedFrameRecoversViaStallWithinDefinedTime() {
        // A valid header for a large payload whose partial bytes start with the envelope prefix (so the
        // frame ALIGNS and parks), carry the banner, and then never complete (silence).
        link.feed(header(4000) + "{\"v\":0,\"out\":\"COMMAND: boom".toByteArray(Charsets.US_ASCII))
        assertNull(client.readMessage(20))
        assertFalse(client.takeCliLeftCompanion())        // parked, not yet stale

        clock += 2001L                                    // defined time elapses (> the ~2 s frame stall)
        assertNull(client.readMessage(20))
        assertTrue("a stalled truncated frame must be discarded and scanned", client.takeCliLeftCompanion())
    }

    @Test fun bannerFragmentedAcrossReadsIsDetected() {
        link.feed("COMM".toByteArray(Charsets.US_ASCII))  // implausible length so far: held, nothing dropped yet
        assertNull(client.readMessage(20))
        link.feed("AND: more noise here\r\n".toByteArray(Charsets.US_ASCII))
        assertNull(client.readMessage(20))
        assertTrue("a banner split across reads must still match", client.takeCliLeftCompanion())
    }

    @Test fun bannerPrecededByNoiseIsDetected() {
        link.feed("\u0001\u0002\u0003 junk COMMAND: whatever\r\n".toByteArray(Charsets.US_ASCII))
        assertNull(client.readMessage(20))
        assertTrue("a banner after leading noise must be detected", client.takeCliLeftCompanion())
    }

    @Test fun validJsonWithCommandArrivingSlowlyOverTwoSecondsIsDecodedNotFlagged() {
        // A valid frame whose payload contains "COMMAND:", delivered in fragments spread over MORE than
        // the 2 s stall window but always ADVANCING (each inter-byte gap < 2 s). The stall is on
        // inactivity, not total duration, so the frame must still decode and must NOT flag the CLI.
        val bytes = frame("{\"v\":0,\"t\":4,\"id\":5,\"d\":{\"out\":\"COMMAND: status spanning several slow fragments over time\"}}")
        val parts = bytes.toList().chunked((bytes.size + 3) / 4).map { it.toByteArray() } // 4 fragments
        var msg: org.json.JSONObject? = null
        var elapsed = 0L
        for ((idx, part) in parts.withIndex()) {
            clock += 900L            // each gap < FRAME_STALL_MS, but four of them sum to > 2 s
            elapsed += 900L
            link.feed(part)
            msg = client.readMessage(20)
            if (idx < parts.lastIndex) {
                assertNull("frame not complete yet", msg)
                assertFalse("still advancing: must not flag the CLI", client.takeCliLeftCompanion())
            }
        }
        assertTrue("fragments must span > 2 s (the stall window)", elapsed > 2000L)
        assertNotNull("a slowly-advancing valid frame must still decode", msg)
        assertEquals(5, msg!!.getInt("id"))
        assertFalse("valid JSON containing COMMAND: is not the CLI", client.takeCliLeftCompanion())
    }

    @Test fun commandInsideValidJsonPayloadIsNotAFalseAlarm() {
        // A perfectly valid frame whose payload text contains "COMMAND:" must NOT trip the detector.
        val msg = client.let {
            link.feed(frame("{\"v\":0,\"t\":4,\"id\":7,\"d\":{\"ok\":true,\"out\":\"COMMAND: help shown\"}}"))
            it.readMessage(20)
        }
        assertNotNull("the valid frame must decode", msg)
        assertEquals(7, msg!!.getInt("id"))
        assertFalse("a literal COMMAND: inside valid JSON is not the CLI", client.takeCliLeftCompanion())
    }
}
