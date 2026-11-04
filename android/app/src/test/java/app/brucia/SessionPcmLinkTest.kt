package app.brucia

import app.brucia.audio.*
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SessionPcmLinkTest {
    private class Wire : Transport {
        val frames = mutableListOf<JSONObject>()
        var reply: (JSONObject) -> Unit = {}
        override var isOpen = true
        override fun write(bytes: ByteArray) {
            assertTrue(bytes.size <= 8196)
            val frame = JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))
            frames += frame; reply(frame)
        }
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
    }
    private fun ack(frame: JSONObject, ok: Boolean = true) = JSONObject().put("t", Kind.ACK)
        .put("id", frame.getInt("id")).put("d", JSONObject().put("ok", ok).put("out", if (ok) "ok" else "rejected"))

    @Test(timeout = 5000) fun speakerUsesFirmwareJsonFieldsAndExactLittleEndianPcm() = runBlocking {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        wire.reply = { session.route(ack(it)) }
        val link = SessionPcmLink(session, PcmStreamSpec(), { true })
        assertTrue(link.begin().first)
        val samples = ShortArray(2048).apply { this[0] = 0x1234; this[1] = -2 }
        assertTrue(link.data(samples, samples.size).ok); assertTrue(link.stop().first)
        val bodies = wire.frames.map { it.getJSONObject("d") }
        assertEquals(listOf("spkr_pcm_begin", "spkr_pcm_data", "spkr_pcm_stop"), bodies.map { it.getString("cmd") })
        assertEquals(16000, bodies[0].getInt("sr"))
        assertArrayEquals(PcmSource.shortsToLittleEndian(samples), Base64.getDecoder().decode(bodies[1].getString("pcm")))
        assertFalse(session.hasPending)
    }

    @Test(timeout = 5000) fun fmPreservesTextArgumentsAndFrameSize() = runBlocking {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        wire.reply = { session.route(ack(it)) }
        val link = SessionPcmLink(session, PcmStreamSpec(100000), { true })
        link.begin(); link.data(shortArrayOf(1, -1), 2); link.stop()
        val commands = wire.frames.map { it.getJSONObject("d").getString("cmd") }
        assertEquals(listOf("fm_audio_begin 100000 phone", "fm_audio_data AQD//w==", "fm_audio_stop"), commands)
        SessionPcmLink(session, PcmStreamSpec(100000, true), { true }).begin()
        assertEquals("fm_audio_begin 100000 mic", wire.frames.last().getJSONObject("d").getString("cmd"))
    }

    @Test(timeout = 5000) fun beginFailureOnRealWireNeverSendsPcm() = runBlocking {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        wire.reply = { session.route(ack(it, it.getJSONObject("d").getString("cmd") != "spkr_pcm_begin")) }
        val service = PcmStreamService(this, { SessionPcmLink(session, it, { true }) }, Dispatchers.Unconfined)
        service.start(PcmStreamSpec(), "prepare", "play") { PcmStreamSource(PcmSource.arrayReader(ShortArray(16))) }
        until { !service.status.value.active }
        assertEquals(listOf("spkr_pcm_begin", "spkr_pcm_stop"), wire.frames.map { it.getJSONObject("d").getString("cmd") })
        assertEquals("rejected", service.status.value.message)
        assertFalse(session.hasPending)
    }

    @Test(timeout = 5000) fun structuredWaitTimeoutAndCancellationReleasePendingRequests() = runBlocking {
        for (cancel in listOf(false, true)) {
            val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
            val link = SessionPcmLink(session, PcmStreamSpec(), { true }, timeoutMs = 30)
            val wait = async { link.begin() }
            until { wire.frames.isNotEmpty() }
            if (cancel) { wait.cancelAndJoin() } else assertFalse(wait.await().first)
            session.route(ack(wire.frames.single()))
            assertFalse(session.hasPending)
        }
    }

    @Test(timeout = 5000) fun oldOrClosedLinksAndOversizeFramesCannotWrite() = runBlocking {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        var current = true
        val link = SessionPcmLink(session, PcmStreamSpec(), { current })
        assertTrue(runCatching { link.data(ShortArray(2049), 2049) }.isFailure)
        current = false
        assertTrue(runCatching { link.stop() }.isFailure)
        current = true; session.close()
        assertTrue(runCatching { link.begin() }.isFailure)
        assertTrue(wire.frames.isEmpty())
    }

    @Test(timeout = 5000) fun fmDataRepliesExposeAcceptedCountsOrUnknownForOldFirmware() = runBlocking {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        var d = JSONObject()
        wire.reply = { session.route(JSONObject().put("t", Kind.ACK).put("id", it.getInt("id")).put("d", d)) }
        val link = SessionPcmLink(session, PcmStreamSpec(100000), { true })
        val frame = ShortArray(1024)
        d = JSONObject().put("ok", true).put("out", "ring full: accepted 300 of 1024 samples")
            .put("samples", 1024).put("accepted", 300).put("free", 0).put("lost", 12)
        assertEquals(PcmDataReply(true, "ring full: accepted 300 of 1024 samples", 300, 1024, 0, 12L), link.data(frame, 1024))
        d = JSONObject().put("ok", true) // pre-accounting firmware: bare ACK
        assertEquals(PcmDataReply(true, ""), link.data(frame, 1024))
        d = JSONObject().put("ok", true).put("accepted", "lots").put("samples", 1.5).put("free", -4)
        val bad = link.data(frame, 1024)
        assertEquals(listOf(-1, -1, -1), listOf(bad.accepted, bad.received, bad.free))
        d = JSONObject().put("ok", false).put("out", "FM audio not active").put("samples", 0).put("accepted", 0)
        assertFalse(link.data(frame, 1024).ok)
        assertEquals(PcmDataReply(false, "device error", null, null, null, null),
            parseFmDataReply(false, JSONObject().put("code", 400).put("msg", "device error")))
        assertFalse(session.hasPending)
        assertTrue(wire.frames.all { it.getJSONObject("d").getString("cmd").startsWith("fm_audio_data ") })
    }
}
