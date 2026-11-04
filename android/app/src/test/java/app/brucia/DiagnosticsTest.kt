package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.protocol.LinkCounters
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import app.brucia.wardrive.DeviceDiagState
import app.brucia.wardrive.DeviceDiagnostics
import app.brucia.wardrive.DeviceInfo
import app.brucia.wardrive.Diagnostics
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Inbound byte chunks for the decoder; outbound writes are dropped. */
private class ByteQueueTransport : Transport {
    val inbound = ArrayDeque<ByteArray>()
    override var isOpen = true
    override fun write(bytes: ByteArray) {}
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val chunk = inbound.removeFirstOrNull() ?: return 0
        chunk.copyInto(buf)
        return chunk.size
    }
    override fun close() { isOpen = false }
}

private fun rawFrame(payload: ByteArray): ByteArray {
    val n = payload.size
    return byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + payload
}

private fun jsonFrame(obj: JSONObject) = rawFrame(obj.toString().toByteArray(Charsets.UTF_8))

/** A diag ACK body as current firmware sends it (proto/PROTOCOL.md "Recovery diagnostics"). */
private fun diagBody(): JSONObject {
    val err = JSONObject().put("400", 0).put("404", 1).put("409", 2).put("413", 0).put("4xx", 0)
        .put("5xx", 0).put("other", 0)
    val link = JSONObject().put("sessions", 2).put("rx_frames", 412).put("tx_frames", 980)
        .put("rx_zero_len", 0).put("rx_oversize", 1).put("rx_alloc_fail", 0).put("rx_stale", 3)
        .put("rx_bad_json", 4).put("busy_409", 2).put("err", err)
    return JSONObject().put("ok", true).put("v", 1).put("reset", "brownout").put("reset_code", 9)
        .put("uptime_s", 90_061)
        .put("heap", JSONObject().put("free", 181_220).put("min_free", 120_440).put("largest", 110_580))
        .put("link", link)
        .put("drops", JSONObject().put("fm_pcm_lost", 7).put("fm_pcm_active", false).put("mic_pcm_dropped", 0))
}

private fun caps(features: JSONObject?) = DeviceInfo.fromCaps(
    JSONObject().put("board", "b").put("fw", "f").apply { if (features != null) put("features", features) })

class DiagnosticsTest {

    @Test fun parsesFullDiagReply() {
        val d = DeviceDiagnostics.fromAck(diagBody())!!
        assertEquals("brownout", d.reset)
        assertEquals(9L, d.resetCode)
        assertEquals(90_061L, d.uptimeS)
        assertEquals(120_440L, d.heap.minFree)
        assertNull(d.psram)
        assertEquals(DeviceDiagnostics.LINK_KEYS, d.link.keys.toList())
        assertEquals(DeviceDiagnostics.ERR_KEYS, d.errors.keys.toList())
        assertEquals(4L, d.link["rx_bad_json"])
        assertEquals(7L, d.fmPcmLost)
        assertEquals(false, d.fmPcmActive)
        assertEquals(0L, d.micPcmDropped)
        val lines = d.lines()
        assertTrue(lines[0], lines[0] == "Device reset reason: brownout (code 9), uptime 1d 01:01:01")
        assertTrue(lines.contains("Device rejected frames: 4 bad JSON, 1 oversize, 0 zero length, 0 alloc failure, 3 stale partial"))
        assertTrue(lines.contains("Device ERR replies: 404 x1, 409 x2 (busy during blocking ops: 2)"))
        assertTrue(lines.contains("Device FM PCM lost samples: 7 (since last FM start)"))
        assertTrue(lines.none { it.startsWith("Device PSRAM") })
    }

    @Test fun psramAndSaturationAndOptionalDrops() {
        val body = diagBody()
            .put("psram", JSONObject().put("size", 8_386_295).put("free", 8_100_000).put("min_free", 8_000_000).put("largest", 8_257_524))
            .put("drops", JSONObject())
        body.getJSONObject("link").put("tx_frames", DeviceDiagnostics.U32_MAX)
        val d = DeviceDiagnostics.fromAck(body)!!
        assertEquals(8_386_295L, d.psram?.size)
        assertNull(d.fmPcmLost)
        assertNull(d.micPcmDropped)
        assertTrue(d.lines().any { it.contains("4294967295+ frames out") })
        assertTrue(d.lines().any { it.startsWith("Device PSRAM: 8100000 of 8386295 B free") })
        assertTrue(d.lines().none { it.contains("FM PCM") || it.contains("mic PCM") })
        assertEquals("Device ERR replies: none (busy during blocking ops: 0)",
            DeviceDiagnostics.fromAck(diagBody().also { b ->
                b.getJSONObject("link").put("busy_409", 0).getJSONObject("err").put("404", 0).put("409", 0)
            })!!.lines().first { it.startsWith("Device ERR") })
    }

    @Test fun rejectsMalformedReplies() {
        fun bad(mutate: (JSONObject) -> Unit) {
            val body = diagBody().also(mutate)
            assertNull(body.toString(), DeviceDiagnostics.fromAck(body))
        }
        assertNull(DeviceDiagnostics.fromAck(null))
        bad { it.put("ok", false) }
        bad { it.remove("v") }
        bad { it.put("reset", "Exploded!") }
        bad { it.put("reset", 3) }
        bad { it.remove("uptime_s") }
        bad { it.put("uptime_s", -1) }
        bad { it.put("uptime_s", DeviceDiagnostics.U32_MAX + 1) }
        bad { it.put("uptime_s", 1.5) }
        bad { it.put("uptime_s", "12") }
        bad { it.remove("heap") }
        bad { it.getJSONObject("heap").remove("largest") }
        bad { it.put("psram", JSONObject().put("free", 1)) }
        bad { it.getJSONObject("link").remove("rx_bad_json") }
        bad { it.getJSONObject("link").put("rx_frames", true) }
        bad { it.getJSONObject("link").remove("err") }
        bad { it.getJSONObject("link").getJSONObject("err").remove("5xx") }
        // Unknown extra keys and future reset names are tolerated.
        assertNotNull(DeviceDiagnostics.fromAck(diagBody().put("future", 1).put("reset", "new_reason")))
        // Invalid drop values are omitted, not fatal.
        assertNull(DeviceDiagnostics.fromAck(diagBody().put("drops", JSONObject().put("fm_pcm_lost", -3)))!!.fmPcmLost)
    }

    @Test fun oldFirmwareIsNotSupportedAndStateSurvivesCapsRefresh() {
        val old = caps(JSONObject().put("verified_files", true))
        assertFalse(old.features.diag)
        assertFalse(caps(null).features.diag)
        assertFalse(caps(JSONObject().put("diag", "true")).features.diag)
        assertEquals(DeviceDiagState.Unsupported, Diagnostics.afterCaps(old, DeviceDiagState.NoDevice))
        assertEquals(listOf("Device diagnostics: not supported by this firmware"),
            Diagnostics.deviceLines(DeviceDiagState.Unsupported))
        val current = caps(JSONObject().put("diag", true))
        assertTrue(current.features.diag)
        assertEquals(DeviceDiagState.NotFetched, Diagnostics.afterCaps(current, DeviceDiagState.Unsupported))
        assertEquals(DeviceDiagState.NotFetched, Diagnostics.afterCaps(current, DeviceDiagState.Failed("x")))
        val ready = DeviceDiagState.Ready(DeviceDiagnostics.fromAck(diagBody())!!, "12:00:00")
        assertEquals(ready, Diagnostics.afterCaps(current, ready))
        assertEquals(DeviceDiagState.Loading, Diagnostics.afterCaps(current, DeviceDiagState.Loading))
        // Downgrade to firmware without diag drops the old result.
        assertEquals(DeviceDiagState.Unsupported, Diagnostics.afterCaps(old, ready))
        val lines = Diagnostics.deviceLines(ready)
        assertEquals("Device diagnostics fetched at 12:00:00 (counters since device boot)", lines.first())
        assertEquals(1 + ready.diag.lines().size, lines.size)
        assertEquals(listOf("Device diagnostics: failed (device busy)"), Diagnostics.deviceLines(DeviceDiagState.Failed("device busy")))
    }

    @Test fun decoderCountsMalformedFramesInsteadOfSilentlySkipping() {
        val t = ByteQueueTransport()
        val counters = LinkCounters()
        val c = CompanionClient(t, counters)
        val pong = JSONObject().put("v", 0).put("t", Kind.PONG).put("id", 1).put("d", JSONObject())
        // One chunk: malformed, array (valid JSON but not an object), valid frame.
        t.inbound += rawFrame("{not json".toByteArray()) + rawFrame("[1,2]".toByteArray()) + jsonFrame(pong)
        assertEquals(Kind.PONG, c.readMessage(50)?.getInt("t"))
        var s = counters.snapshot()
        assertEquals(2L, s.malformedFrames)
        assertEquals(1L, s.framesReceived)
        c.hello()
        c.cmd("info")
        assertEquals(2L, counters.snapshot().framesSent)
        // Invalid length: buffer dropped, counted, the link is reset by the caller.
        t.inbound += byteArrayOf(0, 0, 0, 0)
        try { c.readMessage(50); fail("expected IOException") } catch (_: java.io.IOException) {}
        t.inbound += byteArrayOf(1, 0x20, 0, 0) // 8193
        try { c.readMessage(50); fail("expected IOException") } catch (_: java.io.IOException) {}
        s = counters.snapshot()
        assertEquals(2L, s.invalidLengthFrames)
        assertEquals(2L, s.malformedFrames)
        assertTrue(s.lines()[1], s.lines()[1] == "App skipped frames: 2 malformed, 2 invalid length (link reset)")
    }

    @Test fun sessionCountsTimeoutsAndUnmatchedReplies() = runBlocking {
        val t = ByteQueueTransport()
        val counters = LinkCounters()
        val session = CommandSession(CompanionClient(t, counters), t)
        assertFalse(session.await("info", 20).first)
        assertFalse(session.awaitJson("diag", 20).first)
        assertEquals(2L, counters.snapshot().timeouts)
        // The reply to a timed-out request arrives late: no pending owner, counted once.
        session.route(JSONObject().put("t", Kind.ACK).put("id", 999_999).put("d", JSONObject().put("ok", true)))
        assertEquals(1L, counters.snapshot().unmatchedReplies)
        // A matched reply is not counted.
        val id = session.nextId()
        var got = false
        session.expect(id) { _, _ -> got = true }
        session.route(JSONObject().put("t", Kind.ACK).put("id", id).put("d", JSONObject().put("ok", true)))
        assertTrue(got)
        assertEquals(1L, counters.snapshot().unmatchedReplies)
        assertEquals(2L, counters.snapshot().timeouts)
    }

    @Test fun exportLinesCombineAppAndDevice() {
        val counters = LinkCounters()
        counters.noteLink(); counters.noteMalformedFrame(); counters.noteTimeout()
        val lines = Diagnostics.exportLines(counters.snapshot(), DeviceDiagState.Unsupported)
        assertEquals(counters.snapshot().lines(), lines.take(3))
        assertTrue(lines[0].startsWith("App link since app start: 1 link(s)"))
        assertTrue(lines[1].contains("1 malformed"))
        assertTrue(lines[2].contains("1 timed out"))
        assertEquals("Device diagnostics: not supported by this firmware", lines.last())
        assertTrue(Diagnostics.exportLines(counters.snapshot(), DeviceDiagState.NoDevice).last().contains("no device connected"))
    }
}
