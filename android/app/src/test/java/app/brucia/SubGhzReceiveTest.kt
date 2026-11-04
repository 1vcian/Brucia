package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandSession
import app.brucia.session.StreamSubscriptions
import app.brucia.subghz.RawCompleteness
import app.brucia.subghz.SubGhzRawEvent
import app.brucia.subghz.SubGhzReceiveState
import app.brucia.transport.Transport
import app.brucia.wardrive.DeviceCaps
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** S11: Sub-GHz receive UI state follows the real SUB outcome; raw completeness metadata. */
class SubGhzReceiveTest {
    /** Fake device: answers each SUB through [reply] (null = no answer). */
    private class Device : Transport {
        val frames = mutableListOf<JSONObject>()
        var reply: (JSONObject) -> JSONObject? = { null }
        lateinit var session: CommandSession
        override var isOpen = true
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
        override fun write(bytes: ByteArray) {
            val frame = JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))
            frames.add(frame)
            reply(frame)?.let { session.route(it) }
        }
    }
    private fun ack(f: JSONObject) = JSONObject().put("t", Kind.ACK).put("id", f.getInt("id"))
        .put("d", JSONObject().put("ok", true).put("out", "ok"))
    private fun err(f: JSONObject, code: Int, msg: String) = JSONObject().put("t", Kind.ERR).put("id", f.getInt("id"))
        .put("d", JSONObject().put("code", code).put("msg", msg))

    private class Fixture(scope: CoroutineScope, caps: DeviceCaps = DeviceCaps(cc1101 = true)) {
        val device = Device()
        val session = CommandSession(CompanionClient(device), device).also { device.session = it }
        val rx = SubGhzReceiveState()
        val subs = StreamSubscriptions(scope, { session }, { caps },
            onState = { s, on -> rx.onState(s, on) }, onError = rx::onError,
            dispatcher = Dispatchers.Unconfined, timeoutMs = 30)
        fun set(stream: String, on: Boolean) { rx.request(stream, on); subs.set(stream, on) }
    }

    @Test(timeout = 5000) fun rawStartRejectedWith503TurnsListenOffAndShowsError() = runBlocking<Unit> {
        val f = Fixture(this)
        f.device.reply = { err(it, 503, "sub-ghz receiver init failed") }
        f.set("subghz.raw", true)
        assertEquals("subghz.raw", f.device.frames.single().getJSONObject("d").getString("stream"))
        assertFalse(f.rx.listening.value)
        assertEquals("sub-ghz receiver init failed", f.rx.rawError.value)
        assertTrue(f.subs.activeStreams().isEmpty())
        // A retry clears the stale error; an ACK keeps Listen on.
        f.device.reply = { ack(it) }
        f.set("subghz.raw", true)
        assertTrue(f.rx.listening.value)
        assertEquals("", f.rx.rawError.value)
    }

    @Test(timeout = 5000) fun spectrumStartRejectedTurnsSpectrumOffAndShowsError() = runBlocking<Unit> {
        val f = Fixture(this)
        f.device.reply = { err(it, 503, "CC1101 init failed") }
        f.set("subghz.spectrum", true)
        assertFalse(f.rx.spectrumActive.value)
        assertEquals("CC1101 init failed", f.rx.spectrumError.value)
        assertEquals("", f.rx.rawError.value)
    }

    @Test(timeout = 5000) fun missingAckTurnsListenOffAfterTheDeadline() = runBlocking<Unit> {
        val f = Fixture(this)
        f.set("subghz.raw", true)
        assertTrue(f.rx.listening.value) // optimistic while pending
        withTimeout(2000) { while (f.rx.listening.value) delay(5) }
        assertEquals("No stream acknowledgement from the device", f.rx.rawError.value)
    }

    @Test(timeout = 5000) fun stopFailuresAndSupersededStartsAreNotReportedAsListenErrors() = runBlocking<Unit> {
        val f = Fixture(this)
        f.device.reply = { ack(it) }
        f.set("subghz.raw", true)
        assertTrue(f.rx.listening.value)
        // Exclusive peer start supersedes raw: Listen turns off without an error.
        f.set("subghz.spectrum", true)
        assertFalse(f.rx.listening.value)
        assertTrue(f.rx.spectrumActive.value)
        assertEquals("", f.rx.rawError.value)
        // A rejected OFF is not a receive failure.
        f.device.reply = { err(it, 500, "busy") }
        f.set("subghz.spectrum", false)
        assertFalse(f.rx.spectrumActive.value)
        assertEquals("", f.rx.spectrumError.value)
        f.rx.reset()
        assertFalse(f.rx.listening.value || f.rx.spectrumActive.value)
    }

    @Test(timeout = 5000) fun missingHardwareStartIsReportedWithoutWriting() = runBlocking<Unit> {
        val f = Fixture(this, DeviceCaps())
        f.set("subghz.raw", true)
        assertTrue(f.device.frames.isEmpty())
        assertFalse(f.rx.listening.value)
        assertEquals("No confirmed hardware for subghz.raw", f.rx.rawError.value)
    }

    @Test fun unrelatedStreamsAreIgnored() {
        val rx = SubGhzReceiveState()
        rx.request("wifi.ap", true)
        assertFalse(rx.onState("wifi.ap", true))
        rx.onError("wifi.ap", "x")
        assertEquals("", rx.rawError.value)
        assertEquals("", rx.spectrumError.value)
    }

    private fun raw(symbols: Any?, max: Any?): JSONObject {
        val o = JSONObject().put("freq", 433_920_000L).put("timings", JSONArray(listOf(300, 900, 300, 900, 300)))
        if (symbols != null) o.put("symbols", symbols)
        if (max != null) o.put("max_symbols", max)
        return o
    }

    @Test fun rawCompletenessMetadataIsParsedConservatively() {
        val old = SubGhzRawEvent.parse(raw(null, null))!!
        assertEquals(433_920_000L, old.freqHz)
        assertArrayEquals(intArrayOf(300, 900, 300, 900, 300), old.timings)
        assertNull(old.symbols)
        assertEquals(RawCompleteness.NO_INFO, old.completeness)
        assertEquals(RawCompleteness.NOT_BUFFER_CUT, SubGhzRawEvent.parse(raw(3, 64))!!.completeness)
        val full = SubGhzRawEvent.parse(raw(64, 64))!!
        assertEquals(RawCompleteness.UNKNOWN_AT_LIMIT, full.completeness)
        assertEquals(64, full.symbols)
        assertEquals(64, full.maxSymbols)
        // Half-present, inconsistent or malformed metadata carries no information.
        for ((s, m) in listOf(64 to null, null to 64, 65 to 64, 1 to 0, -1 to 64, 3.5 to 64, "64" to 64)) {
            val e = SubGhzRawEvent.parse(raw(s, m))!!
            assertEquals("symbols=$s max=$m", RawCompleteness.NO_INFO, e.completeness)
            assertNull(e.symbols); assertNull(e.maxSymbols)
        }
        assertNull(SubGhzRawEvent.parse(JSONObject().put("freq", 1)))
    }
}
