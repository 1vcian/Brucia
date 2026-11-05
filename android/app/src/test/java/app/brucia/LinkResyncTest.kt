package app.brucia

import app.brucia.audio.PcmSource
import app.brucia.audio.PcmStreamSpec
import app.brucia.audio.SessionPcmLink
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.FrameBudget
import app.brucia.protocol.Kind
import app.brucia.protocol.LinkCounters
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import app.brucia.wardrive.DeviceInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-to-host resynchronisation after stray text (proto/PROTOCOL.md "Resynchronisation")
 * and the rx_max frame budget (proto/PROTOCOL.md "Inbound frame capacity").
 */
class LinkResyncTest {
    private class Wire : Transport {
        val inbound = ArrayDeque<ByteArray>()
        val written = mutableListOf<ByteArray>()
        var reply: (JSONObject) -> Unit = {}
        override var isOpen = true
        override fun write(bytes: ByteArray) {
            written += bytes
            reply(JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8)))
        }
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val chunk = inbound.removeFirstOrNull() ?: return 0
            chunk.copyInto(buf)
            return chunk.size
        }
        override fun close() { isOpen = false }
    }

    private fun frame(id: Int): ByteArray {
        val payload = """{"v":0,"t":${Kind.PONG},"id":$id,"d":{}}""".toByteArray()
        val n = payload.size
        return byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + payload
    }

    private val text = "cc1101 Connection OK\r\n".toByteArray()

    private fun drain(c: CompanionClient): List<Int> = generateSequence { c.readMessage(5) }.map { it.getInt("id") }.toList()

    @Test fun textBetweenFramesIsSkippedAndCounted() {
        val wire = Wire(); val counters = LinkCounters(); val c = CompanionClient(wire, counters)
        wire.inbound += frame(1) + text + frame(2) + text
        wire.inbound += frame(3)
        assertEquals(listOf(1, 2, 3), drain(c))
        val s = counters.snapshot()
        assertEquals(2L * text.size, s.noiseBytes)
        assertEquals(2L, s.invalidLengthFrames) // "cc11" read as a length, once per noise run
        assertEquals(3L, s.framesReceived)
        assertTrue(wire.isOpen)
    }

    @Test fun noiseArrivingByteByByteBeforeTheFirstFrame() {
        val wire = Wire(); val counters = LinkCounters(); val c = CompanionClient(wire, counters)
        for (b in text + frame(7)) wire.inbound += byteArrayOf(b)
        val got = mutableListOf<Int>()
        repeat(200) { c.readMessage(1)?.let { got += it.getInt("id") } }
        assertEquals(listOf(7), got)
        assertEquals(text.size.toLong(), counters.snapshot().noiseBytes)
        assertEquals(1L, counters.snapshot().invalidLengthFrames)
    }

    @Test fun textAfterALengthPrefixLosesOnlyThatFrame() {
        val wire = Wire(); val counters = LinkCounters(); val c = CompanionClient(wire, counters)
        val two = frame(2)
        wire.inbound += two.copyOfRange(0, 4) + text + two.copyOfRange(4, two.size) + frame(3)
        assertEquals(listOf(3), drain(c))
        assertEquals((two.size + text.size).toLong(), counters.snapshot().noiseBytes)
    }

    @Test fun textInsideAPayloadIsOneMalformedFrameThenResync() {
        val wire = Wire(); val counters = LinkCounters(); val c = CompanionClient(wire, counters)
        val two = frame(2)
        wire.inbound += two.copyOfRange(0, 20) + text + two.copyOfRange(20, two.size) + frame(3)
        assertEquals(listOf(3), drain(c))
        assertEquals(1L, counters.snapshot().malformedFrames)
        assertEquals(text.size.toLong(), counters.snapshot().noiseBytes) // the displaced tail
    }

    @Test fun plausibleLengthWithoutEnvelopeAndZeroOrOversizeLengthsResync() {
        val wire = Wire(); val counters = LinkCounters(); val c = CompanionClient(wire, counters)
        wire.inbound += byteArrayOf(40, 0, 0, 0) + ByteArray(40) { 'x'.code.toByte() } + frame(1)
        wire.inbound += byteArrayOf(0, 0, 0, 0) + byteArrayOf(1, 0x20, 0, 0) + frame(2)
        assertEquals(listOf(1, 2), drain(c))
        assertEquals(44L + 8L, counters.snapshot().noiseBytes)
        assertTrue(wire.isOpen)
    }

    @Test fun trailingNoiseWaitsForMoreBytesWithoutThrowing() {
        val wire = Wire(); val counters = LinkCounters(); val c = CompanionClient(wire, counters)
        wire.inbound += text + text
        assertNull(c.readMessage(5))
        assertEquals(2L * text.size - 8, counters.snapshot().noiseBytes) // last 8 bytes may start a frame
        wire.inbound += frame(9)
        assertEquals(listOf(9), drain(c))
        assertEquals(2L * text.size, counters.snapshot().noiseBytes)
    }

    @Test fun diagnosticsLineShowsNoiseBytes() {
        val counters = LinkCounters()
        counters.noteNoise(22); counters.noteInvalidLength()
        assertEquals("App skipped frames: 0 malformed, 1 invalid length (resynced), 22 noise bytes",
            counters.snapshot().lines()[1])
    }

    @Test fun capsRxMaxParsesAndOlderFirmwareHasNone() {
        val base = JSONObject().put("board", "b").put("fw", "f")
        assertEquals(1792, DeviceInfo.fromCaps(JSONObject(base.toString()).put("rx_max", 1792).put("rx_buf", 2048)).rxMax)
        assertEquals(2048, DeviceInfo.fromCaps(JSONObject(base.toString()).put("rx_buf", 2048)).rxBuf)
        assertNull(DeviceInfo.fromCaps(base).rxMax)
        assertNull(DeviceInfo.fromCaps(JSONObject(base.toString()).put("rx_max", 0)).rxMax)
        assertNull(DeviceInfo.fromCaps(JSONObject(base.toString()).put("rx_max", 9000)).rxMax)
        assertNull(DeviceInfo.fromCaps(JSONObject(base.toString()).put("rx_max", "8192")).rxMax)
        assertEquals(FrameBudget.LEGACY_RX_MAX, FrameBudget.chunkBudget(null))
        assertEquals(FrameBudget.MAX_FRAME, FrameBudget.sendLimit(null))
        assertEquals(1792, FrameBudget.sendLimit(1792))
    }

    @Test fun sendLimitRefusesFramesAboveTheDeviceRxMax() {
        val wire = Wire(); val c = CompanionClient(wire)
        c.sendLimit = 100
        c.cmd("diag")
        val error = runCatching { c.cmd("x".repeat(200)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message!!.contains("receive buffer"))
        assertEquals(1, wire.written.size)
    }

    @Test fun slashEscapesAreUndoneExactly() {
        val c = CompanionClient(Wire())
        assertEquals("""{"a":"//8A/w=="}""", c.unescapeSlashes("""{"a":"\/\/8A\/w=="}"""))
        // An escaped backslash followed by an escaped slash: backslash kept, slash unescaped.
        assertEquals("""{"a":"\\/x"}""", c.unescapeSlashes("""{"a":"\\\/x"}"""))
        // A lone escaped backslash before a plain slash (JVM org.json does not escape '/').
        assertEquals("""{"a":"\\/"}""", c.unescapeSlashes("""{"a":"\\/"}"""))
        assertEquals("""{"a":"\u0001\"q"}""", c.unescapeSlashes("""{"a":"\u0001\"q"}"""))
    }

    @Test fun pcmFramesFitTheReportedRxMaxEvenForSlashHeavyAudio() = runBlocking {
        for ((rxMax, fm) in listOf(1792 to false, 1792 to true, 768 to false, 8192 to false, 8192 to true)) {
            val spec = PcmStreamSpec(fmKHz = if (fm) 100000 else null, rxMax = rxMax)
            val wire = Wire(); val client = CompanionClient(wire).apply { sendLimit = rxMax }
            val session = CommandSession(client, wire)
            wire.reply = { f -> session.route(JSONObject().put("t", Kind.ACK).put("id", f.getInt("id"))
                .put("d", JSONObject().put("ok", true).put("out", "ok"))) }
            // -1 (0xFFFF) encodes to "//8A//8..." base64: the worst case for '/' escaping.
            val samples = ShortArray(spec.frameSamples) { -1 }
            val reply = SessionPcmLink(session, spec, { true }).data(samples, samples.size)
            assertTrue("$rxMax fm=$fm: ${reply.message}", reply.ok)
            assertTrue(wire.written.last().size - 4 <= rxMax)
            assertTrue("frame uses most of the budget", wire.written.last().size - 4 > rxMax - 120 || samples.size == (if (fm) 1024 else 2048))
        }
        // Older firmware (no rx_max) keeps today's frame sizes.
        assertEquals(2048, PcmStreamSpec().frameSamples)
        assertEquals(1024, PcmStreamSpec(fmKHz = 100000).frameSamples)
        assertEquals(2048, PcmStreamSpec(rxMax = 8192).frameSamples)
        assertFalse(PcmStreamSpec(rxMax = 1792).frameSamples >= 2048)
        assertEquals(PcmSource.SAMPLE_RATE, 16000)
    }
}
