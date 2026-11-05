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

    @Test fun writeExceptionCompletesThroughErrPath() = runVirtual {
        t.writeError = java.io.IOException("pipe broken")
        assertEquals(false to "pipe broken", session.await("info", 1000))
        assertFalse(session.hasPending)
    }

    @Test fun transportClosedBeforeOrDuringWriteFails() = runVirtual {
        t.onWrite = { t.close() }
        assertEquals(false to "USB write failed", session.await("info", 1000))
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
}
