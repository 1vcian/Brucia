package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.transport.Transport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake byte pipe: queues inbound chunks, records outbound bytes. */
private class FakeTransport : Transport {
    val inbound = ArrayDeque<ByteArray>()
    val written = ArrayList<Byte>()
    override var isOpen = true
    var readCalls = 0
    override fun write(bytes: ByteArray) { written.addAll(bytes.toList()) }
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        readCalls++
        val chunk = inbound.removeFirstOrNull() ?: return 0
        val n = minOf(chunk.size, buf.size)
        for (i in 0 until n) buf[i] = chunk[i]
        if (n < chunk.size) inbound.addFirst(chunk.copyOfRange(n, chunk.size))
        return n
    }
    override fun close() { isOpen = false }
}

private fun frame(obj: JSONObject): ByteArray {
    val payload = deviceJson(obj).toByteArray(Charsets.UTF_8)
    val len = payload.size
    val head = byteArrayOf(
        (len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte(),
        ((len ushr 16) and 0xFF).toByte(), ((len ushr 24) and 0xFF).toByte(),
    )
    return head + payload
}

private fun envelope(kind: Int, id: Int, d: JSONObject) =
    JSONObject().put("v", 0).put("t", kind).put("id", id).put("d", d)

class ProtocolTest {

    @Test fun helloIsFramedCorrectly() {
        val t = FakeTransport()
        CompanionClient(t).hello()
        val bytes = t.written.toByteArray()
        assertTrue(bytes.size > 4)
        val len = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)
        assertEquals(len, bytes.size - 4)
        val obj = JSONObject(String(bytes, 4, len, Charsets.UTF_8))
        assertEquals(Kind.HELLO, obj.getInt("t"))
        assertEquals("Brucia", obj.getJSONObject("d").getString("app"))
        assertEquals(BuildConfig.VERSION_NAME, obj.getJSONObject("d").getString("appVersion"))
    }

    @Test fun oversizedCommandIsRejectedBeforeWriting() {
        val t = FakeTransport()
        val result = CompanionClient(t).cmd("x".repeat(8192))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("frame limit"))
        assertTrue(t.written.isEmpty())
    }

    @Test fun everyEnvelopeKindReportsBudgetFailureWithoutThrowingOrWriting() {
        val pipe = FakeTransport()
        val client = CompanionClient(pipe).apply { sendLimit = 1 }
        assertTrue(client.hello().isFailure)
        assertTrue(client.ping().isFailure)
        assertTrue(client.cmd("info").isFailure)
        assertTrue(client.cmdFields("info", JSONObject()).isFailure)
        assertTrue(client.sub("wifi.ap", true).isFailure)
        assertTrue(client.sub("wifi.ap", true, 1, "fixture").isFailure)
        assertTrue(pipe.written.isEmpty())
        assertEquals(0L, client.counters.snapshot().framesSent)
        client.sendLimit = 8192
        assertEquals(77, client.cmd("info", 77).getOrThrow())
        assertEquals(1L, client.counters.snapshot().framesSent)
    }

    @Test fun subscriptionAckCannotCompletePendingCommand() {
        var completed = false
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>(101 to { _, _ -> completed = true })
        app.brucia.protocol.routeCommandReply(
            envelope(Kind.ACK, 4, JSONObject().put("ok", true)), pending, mutableMapOf())
        assertTrue(pending.containsKey(101))
        org.junit.Assert.assertFalse(completed)
        app.brucia.protocol.routeCommandReply(
            envelope(Kind.ACK, 101, JSONObject().put("ok", true)), pending, mutableMapOf())
        assertTrue(completed)
        assertTrue(pending.isEmpty())
    }

    @Test fun firmwareErrorCompletesOnlyMatchingRequest() {
        var result: Pair<Boolean, String>? = null
        var jsonOk: Boolean? = null
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>(101 to { ok, out -> result = ok to out })
        val pendingJson = mutableMapOf<Int, (Boolean, JSONObject?) -> Unit>(102 to { ok, _ -> jsonOk = ok })
        app.brucia.protocol.routeCommandReply(
            envelope(Kind.ERR, 101, JSONObject().put("code", 501).put("msg", "Not supported")), pending, pendingJson)
        assertEquals(false to "Not supported", result)
        assertTrue(pendingJson.containsKey(102))
        assertNull(jsonOk)
        app.brucia.protocol.routeCommandReply(
            envelope(Kind.ERR, 102, JSONObject().put("msg", "Missing module")), pending, pendingJson)
        assertEquals(false, jsonOk)
        assertTrue(pendingJson.isEmpty())
    }

    @Test fun reconnectDoesNotReuseAnInFlightRequestId() {
        val first = CompanionClient(FakeTransport()).nextId()
        val second = CompanionClient(FakeTransport()).nextId()
        assertTrue(second > first)
    }

    @Test fun closedTransportDoesNotSpinOnReads() {
        val t = FakeTransport()
        t.close()
        assertNull(CompanionClient(t).readMessage(5000))
        assertEquals(0, t.readCalls)
    }

    @Test fun readsOneFrame() {
        val t = FakeTransport()
        t.inbound.add(frame(envelope(Kind.CAPS, 1, JSONObject().put("board", "x").put("chip", "esp32-s3"))))
        val msg = CompanionClient(t).readMessage(500)
        assertNotNull(msg)
        assertEquals(Kind.CAPS, msg!!.getInt("t"))
        assertEquals("esp32-s3", msg.getJSONObject("d").getString("chip"))
    }

    @Test fun reassemblesChunkedFrame() {
        val t = FakeTransport()
        val full = frame(envelope(Kind.EVENT, 0, JSONObject().put("ev", "wifi.ap")))
        // deliver in three awkward chunks (split header and payload)
        t.inbound.add(full.copyOfRange(0, 2))
        t.inbound.add(full.copyOfRange(2, 7))
        t.inbound.add(full.copyOfRange(7, full.size))
        val msg = CompanionClient(t).readMessage(500)
        assertNotNull(msg)
        assertEquals("wifi.ap", msg!!.getJSONObject("d").getString("ev"))
    }

    @Test fun parsesTwoFramesInSequence() {
        val t = FakeTransport()
        val c = CompanionClient(t)
        t.inbound.add(frame(envelope(Kind.PONG, 2, JSONObject())))
        t.inbound.add(frame(envelope(Kind.ACK, 3, JSONObject().put("ok", true).put("out", "done"))))
        val m1 = c.readMessage(500); val m2 = c.readMessage(500)
        assertEquals(Kind.PONG, m1!!.getInt("t"))
        assertEquals("done", m2!!.getJSONObject("d").getString("out"))
    }

    @Test fun timesOutWhenNoData() {
        assertNull(CompanionClient(FakeTransport()).readMessage(50))
    }

    @Test fun malformedJsonDoesNotHideFollowingBufferedFrame() {
        val t = FakeTransport()
        // Both frames arrive in one read, with no later transport data available. A valid header and
        // envelope prefix with an undecodable body: the boundary is known, the frame is skipped.
        val malformed = byteArrayOf(6, 0, 0, 0) + "{\"v\":}".toByteArray()
        t.inbound.add(malformed + malformed + frame(envelope(Kind.PONG, 701, JSONObject())))
        assertEquals(701, CompanionClient(t).readMessage(20)?.getInt("id"))
        assertEquals("Buffered frames must not wait for another transport read", 1, t.readCalls)
    }

    @Test fun replyWithoutBodyAndThrowingCallbackStillReleaseBothWaiters() {
        // ERR with no "d" (or no msg) still fails the request with a readable message.
        var plain: Pair<Boolean, String>? = null
        var json: Pair<Boolean, JSONObject?>? = null
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>(801 to { ok, out -> plain = ok to out })
        val pendingJson = mutableMapOf<Int, (Boolean, JSONObject?) -> Unit>(801 to { ok, d -> json = ok to d })
        val bare = JSONObject().put("v", 0).put("t", Kind.ERR).put("id", 801)
        assertEquals("Device error", app.brucia.protocol.routeCommandReply(bare, pending, pendingJson))
        assertEquals(false to "Device error", plain)
        assertEquals(false to null, json)
        // ACK with no body is not success.
        var ackOk: Boolean? = null
        app.brucia.protocol.routeCommandReply(JSONObject().put("t", Kind.ACK).put("id", 802),
            mutableMapOf(802 to { ok, _ -> ackOk = ok }), mutableMapOf())
        assertEquals(false, ackOk)
        // A plain callback that throws must not leave the JSON waiter of the same id hanging.
        var released = false
        try {
            app.brucia.protocol.routeCommandReply(envelope(Kind.ACK, 803, JSONObject().put("ok", true)),
                mutableMapOf(803 to { _, _ -> throw IllegalStateException("ui") }),
                mutableMapOf(803 to { _, _ -> released = true }))
            org.junit.Assert.fail("callback error must propagate")
        } catch (_: IllegalStateException) { }
        assertTrue(released)
        // Only ACK/ERR may complete a command.
        try {
            app.brucia.protocol.routeCommandReply(envelope(Kind.PONG, 804, JSONObject()), mutableMapOf(), mutableMapOf())
            org.junit.Assert.fail("PONG routed as a command reply")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun ackSuccessRequiresLiteralBoolean() {
        for (invalid in listOf<Any>("true", 1, JSONObject.NULL)) {
            var result: Boolean? = null
            val pending = mutableMapOf<Int, (Boolean, String) -> Unit>(701 to { ok, _ -> result = ok })
            app.brucia.protocol.routeCommandReply(
                envelope(Kind.ACK, 701, JSONObject().put("ok", invalid)), pending, mutableMapOf())
            assertEquals("Malformed success flag: $invalid", false, result)
        }
    }

    @Test fun concurrentCommandsRemainWholeAndHaveUniqueIds() {
        val pipe = FakeTransport()
        val client = CompanionClient(pipe)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 160).map { index ->
                workers.submit<Int> { client.cmd("info $index").getOrThrow() }
            }
            val ids = tasks.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(160, ids.toSet().size)
            val receiverPipe = FakeTransport()
            receiverPipe.inbound.add(pipe.written.toByteArray())
            val receiver = CompanionClient(receiverPipe)
            val messages = (0 until 160).map { receiver.readMessage(100)!! }
            assertEquals(ids.toSet(), messages.map { it.getInt("id") }.toSet())
            assertEquals((0 until 160).map { "info $it" }.toSet(),
                messages.map { it.getJSONObject("d").getString("cmd") }.toSet())
            assertNull(receiver.readMessage(1))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun completeFrameSurvivesEveryPossibleSplit() {
        val expected = envelope(Kind.EVENT, 701, JSONObject().put("label", "caffè 🔥"))
        val bytes = frame(expected)
        for (split in 1 until bytes.size) {
            val t = FakeTransport()
            t.inbound.add(bytes.copyOfRange(0, split))
            t.inbound.add(bytes.copyOfRange(split, bytes.size))
            assertEquals("Split at $split", expected.toString(), CompanionClient(t).readMessage(100)?.toString())
        }
    }

    @Test fun partialFrameSurvivesReadTimeout() {
        val bytes = frame(envelope(Kind.ACK, 701, JSONObject().put("ok", true)))
        val t = FakeTransport()
        val client = CompanionClient(t)
        t.inbound.add(bytes.copyOfRange(0, 7))
        assertNull(client.readMessage(1))
        t.inbound.add(bytes.copyOfRange(7, bytes.size))
        assertEquals(701, client.readMessage(100)?.getInt("id"))
    }

    @Test fun coalescedFramesStayInOrder() {
        val t = FakeTransport()
        t.inbound.add((701..720).fold(ByteArray(0)) { bytes, id ->
            bytes + frame(envelope(Kind.PONG, id, JSONObject()))
        })
        val client = CompanionClient(t)
        for (id in 701..720) assertEquals(id, client.readMessage(100)?.getInt("id"))
    }

    @Test fun receiveLimitMatchesFirmwareMtu() {
        val t = FakeTransport()
        val client = CompanionClient(t)
        val prefix = "{\"v\":0,\"padding\":\""
        val suffix = "\"}"
        val json = prefix + "x".repeat(8192 - prefix.length - suffix.length) + suffix
        val valid = json.toByteArray(Charsets.UTF_8)
        t.inbound.add(byteArrayOf(0, 32, 0, 0) + valid) // Exactly 8192 bytes.
        assertEquals(8192 - prefix.length - suffix.length, client.readMessage(100)?.getString("padding")?.length)
        // An oversized prefix is skipped as noise without retaining its expected payload.
        t.inbound.add(byteArrayOf(1, 32, 0, 0))
        assertNull(client.readMessage(1))
        t.inbound.add(frame(envelope(Kind.PONG, 702, JSONObject())))
        assertEquals(702, client.readMessage(100)?.getInt("id"))
    }

    @Test fun callbackMayRegisterNextJsonCallbackWithoutLosingIt() {
        val jsonCallbacks = mutableMapOf<Int, (Boolean, JSONObject?) -> Unit>()
        var oldCalls = 0
        var newCalls = 0
        jsonCallbacks[701] = { _, _ -> oldCalls++ }
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>(701 to { _, _ ->
            jsonCallbacks[701] = { _, _ -> newCalls++ }
        })
        app.brucia.protocol.routeCommandReply(
            envelope(Kind.ACK, 701, JSONObject().put("ok", true)), pending, jsonCallbacks)
        assertEquals(1, oldCalls)
        assertEquals(0, newCalls)
        assertTrue(jsonCallbacks.containsKey(701))
    }
}
