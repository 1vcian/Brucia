package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Records each written frame as its decoded {v,t,id,d} envelope; [onWrite] can reply inline. */
private class RecordingTransport : Transport {
    val frames = ArrayList<JSONObject>()
    override var isOpen = true
    var writeError: Exception? = null
    var onWrite: (JSONObject) -> Unit = {}
    override fun write(bytes: ByteArray) {
        writeError?.let { throw it }
        val len = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)
        assertEquals(len, bytes.size - 4)
        val env = JSONObject(String(bytes, 4, len, Charsets.UTF_8))
        frames += env
        onWrite(env)
    }
    override fun read(buf: ByteArray, timeoutMs: Int) = 0
    override fun close() { isOpen = false }
}

private fun reply(kind: Int, id: Int, d: JSONObject) =
    JSONObject().put("v", 0).put("t", kind).put("id", id).put("d", d)

private fun ack(id: Int, out: String) = reply(Kind.ACK, id, JSONObject().put("ok", true).put("out", out))

/**
 * 4-byte little-endian length prefix + the envelope bytes, exactly as the device frames it. Takes a
 * raw string so the envelope begins with the `{"v":` prefix the decoder resyncs on (the firmware emits
 * `v` first; org.json's key order is unspecified, so an assembled JSONObject can't be relied on here).
 */
private fun frameBytes(payload: String): ByteArray {
    val p = payload.toByteArray(Charsets.UTF_8)
    val out = ByteArray(4 + p.size)
    out[0] = (p.size and 0xFF).toByte()
    out[1] = ((p.size shr 8) and 0xFF).toByte()
    out[2] = ((p.size shr 16) and 0xFF).toByte()
    out[3] = ((p.size shr 24) and 0xFF).toByte()
    p.copyInto(out, 4)
    return out
}

/** A transport the test can feed inbound bytes to and drop; records outbound frames like the device link. */
private class FeedableTransport : Transport {
    override var isOpen = true
    val outbound = ArrayList<JSONObject>()
    private val inbound = ArrayDeque<Byte>()
    override fun write(bytes: ByteArray) {
        val len = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)
        outbound += JSONObject(String(bytes, 4, len, Charsets.UTF_8))
    }
    fun feed(bytes: ByteArray) { for (b in bytes) inbound.addLast(b) }
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var i = 0
        while (i < buf.size && inbound.isNotEmpty()) buf[i++] = inbound.removeFirst()
        return i
    }
    override fun close() { isOpen = false }
}

/**
 * The essential read loop of [app.brucia.wardrive.WardriveViewModel]: drain complete frames into the
 * session, and when the link has dropped close the session so pending commands fail (never hang).
 */
private fun pump(client: CompanionClient, session: CommandSession, transport: Transport) {
    while (true) {
        val m = client.readMessage(20)
        if (m != null) { session.route(m); continue }
        if (!transport.isOpen) { session.close("Disconnected"); break }
        break
    }
}

class CommandSessionTest {
    private val t = RecordingTransport()
    private val session = CommandSession(CompanionClient(t), t)

    @Test fun immediateReplyResolvesAndReleases() = runVirtual {
        t.onWrite = { env -> session.route(ack(env.getInt("id"), "done")) }
        assertEquals(true to "done", session.await("info", 1000))
        val sent = t.frames.single()
        assertEquals(Kind.CMD, sent.getInt("t"))
        assertEquals("info", sent.getJSONObject("d").getString("cmd"))
        assertFalse(session.hasPending)
    }

    @Test fun timeoutReleases() = runVirtual {
        assertEquals(false to "No response from the device.", session.await("info", 20))
        assertFalse(session.hasPending)
        val json = session.awaitJson("info", 20)
        assertFalse(json.first)
        assertEquals("No response from the device.", json.second?.getString("msg"))
        assertFalse(session.hasPending)
    }

    @Test fun cancellationReleasesAndPropagates() = runVirtual {
        val request = async(start = CoroutineStart.UNDISPATCHED) { session.await("spam", 10_000) }
        assertTrue(session.hasPending)
        request.cancel()
        try { request.await(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertFalse(session.hasPending)
    }

    @Test fun oversizedCommandsAndFieldsFailImmediatelyWithoutPoisoningSession() = runVirtual {
        for (fields in listOf(false, true)) {
            val result = if (fields) session.awaitFields("info", JSONObject().put("extra", "x".repeat(8192)), 650_000)
                else session.await("x".repeat(8192), 650_000)
            assertFalse(result.first)
            assertTrue(result.second.contains("frame limit"))
            assertFalse(session.hasPending)
            assertTrue(t.frames.isEmpty())
        }
        t.onWrite = { frame -> session.route(ack(frame.getInt("id"), "ok")) }
        assertEquals(true to "ok", session.await("info", 1000))
    }

    @Test fun writeExceptionCompletesThroughErrPath() = runVirtual {
        t.writeError = java.io.IOException("pipe broken")
        assertEquals(false to "pipe broken", session.await("info", 1000))
        assertFalse(session.hasPending)
    }

    @Test fun transportClosedBeforeOrDuringWriteFails() = runVirtual {
        t.onWrite = { t.close() }
        assertEquals(false to "USB write failed: connection closed", session.await("info", 1000))
        assertEquals(1, t.frames.size)
        assertEquals(false to "USB connection is closed or changed", session.await("info", 1000))
        assertEquals(1, t.frames.size)
        assertFalse(session.hasPending)
    }

    @Test fun preflightRejectionFailsWithoutWriting() = runVirtual {
        val guarded = CommandSession(CompanionClient(t), t) { cmd ->
            check(!cmd.startsWith("ibutton_")) { "No confirmed iButton interface" }
        }
        assertEquals(false to "No confirmed iButton interface", guarded.await("ibutton_read", 1000))
        val json = guarded.awaitJson("ibutton_read", 1000)
        assertFalse(json.first)
        assertEquals("No confirmed iButton interface", json.second?.getString("msg"))
        assertTrue(t.frames.isEmpty())
        assertFalse(guarded.hasPending)
    }

    @Test fun concurrentRequestsResolveOnlyByTheirOwnId() = runVirtual {
        val first = async(start = CoroutineStart.UNDISPATCHED) { session.await("a", 10_000) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { session.await("b", 10_000) }
        val (idA, idB) = t.frames.map { it.getInt("id") }
        assertNotEquals(idA, idB)
        assertEquals("out-b", session.route(ack(idB, "out-b")))
        assertEquals(true to "out-b", second.await())
        assertTrue(first.isActive)
        assertTrue(session.hasPending)
        session.route(reply(Kind.ERR, idA, JSONObject().put("msg", "bad arg")))
        assertEquals(false to "bad arg", first.await())
        assertFalse(session.hasPending)
    }

    @Test fun closeFailsEachPendingOnceAndIgnoresLateReplies() = runVirtual {
        val text = async(start = CoroutineStart.UNDISPATCHED) { session.await("a", 10_000) }
        val json = async(start = CoroutineStart.UNDISPATCHED) { session.awaitJson("b", 10_000) }
        val (idA, idB) = t.frames.map { it.getInt("id") }
        session.close("Link lost")
        session.close("Again")
        assertEquals(false to "Link lost", text.await())
        val jsonResult = json.await()
        assertFalse(jsonResult.first)
        assertEquals("Link lost", jsonResult.second?.getString("msg"))
        assertEquals("", session.route(ack(idA, "late")))
        assertEquals("", session.route(ack(idB, "late")))
        assertFalse(session.hasPending)
        assertTrue(session.isClosed)
    }

    @Test fun sendAfterCloseFailsWithoutWriting() = runVirtual {
        session.close()
        assertEquals(false to "USB connection is closed or changed", session.await("info", 1000))
        assertTrue(t.frames.isEmpty())
        assertFalse(session.hasPending)
    }

    @Test fun awaitJsonDeliversFullDataObject() = runVirtual {
        t.onWrite = { env ->
            session.route(reply(Kind.ACK, env.getInt("id"),
                JSONObject().put("ok", true).put("out", "2 keys").put("found", JSONArray().put("A0").put("B1"))))
        }
        val (ok, data) = session.awaitJson("mifare_keycheck", 1000)
        assertTrue(ok)
        assertEquals("2 keys", data?.getString("out"))
        assertEquals(2, data?.getJSONArray("found")?.length())
    }
    @Test fun closeInvokesEachCallbackOnceEvenWhenOneThrows() {
        val errors = ArrayList<Exception>()
        val guarded = CommandSession(CompanionClient(t), t, onCallbackError = { errors += it })
        val calls = ArrayList<String>()
        guarded.expect(201) { _, _ -> calls += "throws"; throw IllegalStateException("ui gone") }
        guarded.expect(202) { ok, out -> calls += "$ok:$out" }
        guarded.close("Link lost")
        guarded.close("Again")
        guarded.fail(202, "late")
        assertEquals(listOf("throws", "false:Link lost"), calls)
        assertEquals("ui gone", errors.single().message)
    }

    @Test fun expectTransmitFailAndReleaseShareOneCompletion() {
        val results = ArrayList<Pair<Boolean, String>>()
        session.expect(301) { ok, out -> results += ok to out }
        session.transmit("fs_list /", 301)
        assertEquals(301, t.frames.single().getInt("id"))
        session.fail(301, "No stream acknowledgement from the device")
        session.route(ack(301, "late"))
        assertEquals(listOf(false to "No stream acknowledgement from the device"), results)
        session.expectJson(302) { _, _ -> fail("Released callback invoked") }
        session.release(302)
        session.route(ack(302, "ignored"))
        assertFalse(session.hasPending)
    }

    @Test fun subscribeWritesSubFrameAndReportsWriteFailure() {
        val results = ArrayList<Pair<Boolean, String>>()
        session.expect(401) { ok, out -> results += ok to out }
        session.subscribe("wifi.monitor", true, 6, "aa:bb:cc:dd:ee:ff", 401)
        val sub = t.frames.single()
        assertEquals(Kind.SUB, sub.getInt("t"))
        assertEquals(6, sub.getJSONObject("d").getInt("channel"))
        assertTrue(session.hasPending)
        t.writeError = java.io.IOException("stall")
        session.expect(402) { ok, out -> results += ok to out }
        session.subscribe("wifi.monitor", false, null, null, 402)
        assertEquals(listOf(false to "stall"), results)
    }

    /**
     * The device left companion mode but USB stayed OPEN (backlog F23, point 2 - the real fault): after
     * a torn frame the firmware drops the companion loop and returns to its serial CLI, which echoes our
     * frames as "COMMAND:". `transport.isOpen` stays true, so without detection a pending command would
     * wait out its timeout (up to 650 s). The decoder flags the CLI echo; the read loop fails the
     * pending command AT ONCE (transport NOT closed), and the SAME session re-enters companion and
     * serves a new command. No artificial transport.close().
     */
    @Test(timeout = 5_000) fun truncatedFrameThenCliEchoFailsPendingFastThenSameSessionRecovers() = runVirtual {
        val link = FeedableTransport()
        val client = CompanionClient(link)
        val s = CommandSession(client, link)

        // A command is dispatched and awaiting its ACK (a long device timeout, as real commands use).
        val pending = async(start = CoroutineStart.UNDISPATCHED) { s.await("wifi_scan", 650_000) }
        assertTrue(s.hasPending)

        // THE FAULT: the device sent a valid header but only part of the payload (a truncated frame),
        // then left companion and its CLI echoes our frames as "COMMAND:". The transport stays OPEN.
        link.feed(byteArrayOf(40, 0, 0, 0) + "{\"v\":0,\"t".toByteArray(Charsets.US_ASCII)) // header + partial
        link.feed("COMMAND: {\"v\":0,\"t\":9} unknown command\r\n".toByteArray(Charsets.US_ASCII)) // CLI echo

        // Recovery within a DEFINED time: a bounded number of read cycles (mirrors the VM read loop).
        var recoveredFast = false
        repeat(10) {
            client.readMessage(20)
            if (client.takeCliLeftCompanion()) {
                s.failPending("The device left companion mode (reconnecting).")
                recoveredFast = true
                return@repeat
            }
        }
        assertTrue("the truncated frame + CLI echo must be detected within a bounded number of reads", recoveredFast)
        assertEquals(false to "The device left companion mode (reconnecting).", pending.await())
        assertTrue("USB must stay open (no artificial close)", link.isOpen)
        assertFalse("session must stay usable, not closed", s.isClosed)
        assertFalse(s.hasPending)

        // The device is back in companion: the SAME session serves a new command (no reconnect needed).
        val recovered = async(start = CoroutineStart.UNDISPATCHED) { s.await("info", 650_000) }
        assertTrue(s.hasPending)
        val id = link.outbound.last().getInt("id")
        link.feed(frameBytes("{\"v\":0,\"t\":${Kind.ACK},\"id\":$id,\"d\":{\"ok\":true,\"out\":\"ok\"}}"))
        pump(client, s, link)
        assertEquals(true to "ok", recovered.await())
    }

    /**
     * The other recovery path: the device was unplugged, so the transport closes (EOF). A pending
     * command fails once on the drop and a fresh session on a reconnected link serves a new command.
     */
    @Test(timeout = 5_000) fun unpluggedTransportFailsPendingCommandThenAFreshSessionRecovers() = runVirtual {
        val link1 = FeedableTransport()
        val client1 = CompanionClient(link1)
        val s1 = CommandSession(client1, link1)

        // A command is dispatched and awaiting its ACK.
        val pending = async(start = CoroutineStart.UNDISPATCHED) { s1.await("wifi_scan", 60_000) }
        assertTrue(s1.hasPending)
        assertEquals(1, link1.outbound.size)

        // The device sends a PARTIAL frame: a plausible 40-byte length header and the real envelope
        // prefix, but only part of the payload before the TX tears. It is a recognised frame start the
        // decoder waits to complete - it must NOT surface a message or resolve the pending command.
        val header = byteArrayOf(40, 0, 0, 0)
        link1.feed(header + "{\"v\":0,\"t\"".toByteArray(Charsets.US_ASCII))
        assertNull(client1.readMessage(20))
        assertTrue(s1.hasPending)

        // The link drops (the firmware dropped it after the torn write). The read loop closes the
        // session, so the pending command fails exactly once instead of hanging on the torn frame.
        link1.close()
        pump(client1, s1, link1)
        assertEquals(false to "Disconnected", pending.await())
        assertFalse(s1.hasPending)
        assertTrue(s1.isClosed)

        // Reconnect: a brand-new session over a fresh link serves a new command and gets its ACK.
        val link2 = FeedableTransport()
        val client2 = CompanionClient(link2)
        val s2 = CommandSession(client2, link2)
        val recovered = async(start = CoroutineStart.UNDISPATCHED) { s2.await("info", 60_000) }
        assertTrue(s2.hasPending)
        val id = link2.outbound.single().getInt("id")
        link2.feed(frameBytes("{\"v\":0,\"t\":${Kind.ACK},\"id\":$id,\"d\":{\"ok\":true,\"out\":\"ok\"}}"))
        pump(client2, s2, link2)
        assertEquals(true to "ok", recovered.await())
        assertFalse(s2.hasPending)
    }
}
