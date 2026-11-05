package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.protocol.StreamPolicy
import app.brucia.session.CommandSession
import app.brucia.session.StreamSubscriptions
import app.brucia.transport.Transport
import app.brucia.wardrive.DeviceCaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Fake USB pipe: decodes every outbound frame, serves inbound bytes in caller-chosen chunks. */
private class VirtualWire : Transport {
    override var isOpen = true
    val sent = ArrayList<JSONObject>()
    val inbound = ArrayDeque<ByteArray>()
    var failWrite: (JSONObject) -> Boolean = { false }
    var onFrame: (JSONObject) -> Unit = {}

    override fun write(bytes: ByteArray) {
        val len = le32(bytes)
        assertEquals(len, bytes.size - 4)
        val env = JSONObject(String(bytes, 4, len, Charsets.UTF_8))
        if (failWrite(env)) throw java.io.IOException("stall")
        sent += env
        onFrame(env)
    }

    private var offset = 0

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val chunk = inbound.firstOrNull() ?: return 0
        val n = minOf(chunk.size - offset, buf.size)
        chunk.copyInto(buf, 0, offset, offset + n)
        offset += n
        if (offset == chunk.size) { inbound.removeFirst(); offset = 0 }
        return n
    }

    override fun close() { isOpen = false }

    /** Queue [frames] as one coalesced chunk, or split at seeded random boundaries (1..[maxChunk]). */
    fun deliver(frames: List<ByteArray>, random: Random?, maxChunk: Int = 97) {
        val all = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        if (all.isEmpty()) return
        if (random == null) { inbound.addLast(all); return }
        var i = 0
        while (i < all.size) {
            val n = random.nextInt(1, maxChunk + 1).coerceAtMost(all.size - i)
            inbound.addLast(all.copyOfRange(i, i + n))
            i += n
        }
    }
}

private fun le32(b: ByteArray) = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
    ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)

private fun rawFrame(payload: ByteArray): ByteArray {
    val n = payload.size
    return byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + payload
}

private fun frame(kind: Int, id: Int, d: JSONObject) =
    rawFrame(deviceJson(JSONObject().put("v", 0).put("t", kind).put("id", id).put("d", d)).toByteArray(Charsets.UTF_8))

private fun ackFrame(id: Int, ok: Boolean, out: String) = frame(Kind.ACK, id, JSONObject().put("ok", ok).put("out", out))
private fun errFrame(id: Int, msg: String) = frame(Kind.ERR, id, JSONObject().put("msg", msg))
private fun eventFrame(id: Int, n: Int, padding: Int = 0) =
    frame(Kind.EVENT, id, JSONObject().put("ev", "wifi.sta").put("n", n).put("pad", "x".repeat(padding)))

/** Private-state probes: the contracts under test promise these stay bounded / drain to empty. */
private fun bufferedBytes(c: CompanionClient): Int =
    (CompanionClient::class.java.getDeclaredField("buffer").apply { isAccessible = true }.get(c) as ByteArray).size

private fun Any.mapSize(field: String): Int =
    (javaClass.getDeclaredField(field).apply { isAccessible = true }.get(this) as Map<*, *>).size

private fun CommandSession.pendingCount() = mapSize("pending") + mapSize("pendingJson")

private fun CoroutineScope.childCount() = coroutineContext[Job]!!.children.count()

/** Upper bound of the client receive buffer: one incomplete max frame plus one 4 KiB read. */
private const val BUFFER_BOUND = 4 + 8192 + 4096
private const val NO_RESPONSE = "No response from the device."
private const val NO_STREAM_ACK = "No stream acknowledgement from the device"

/** Mimics the app reader loop: ACK/ERR go to the session, everything else counts as an event. */
private class Reader(val client: CompanionClient, val wire: VirtualWire, val route: (JSONObject) -> Unit) {
    var events = 0
    var replies = 0
    var maxBuffer = 0
    fun pump() {
        // Every delivered byte belongs to a complete frame, so the buffer returns to empty and no
        // read ever waits on the (wall-clock) readMessage deadline at the end of a pump.
        while (wire.inbound.isNotEmpty() || bufferedBytes(client) > 0) {
            val msg = client.readMessage(1)
            maxBuffer = maxOf(maxBuffer, bufferedBytes(client))
            if (msg == null) continue
            when (msg.getInt("t")) {
                Kind.ACK, Kind.ERR -> { replies++; route(msg) }
                else -> events++
            }
        }
    }
}

/**
 * Long-session regression for CommandSession, CompanionClient and StreamSubscriptions over a
 * deterministic virtual transport and a virtual clock: no sleeps, no wall-clock deadlines.
 * Software-only: this does not measure Android heap/GC, USB latency or physical device stress.
 */
class SessionStressTest {

    @Test(timeout = 60_000)
    fun thousandsOfCommandsWithFragmentedRepliesTimeoutsAndCancellationLeaveNoState() {
        val clock = VirtualClock()
        val wire = VirtualWire()
        val client = CompanionClient(wire)
        val session = CommandSession(client, wire)
        val reader = Reader(client, wire) { session.route(it) }
        val scope = CoroutineScope(clock + Job())
        val random = Random(8)
        val perRound = 125
        var maxPending = 0
        var totalEvents = 0
        repeat(40) { round ->
            val calls = (0 until perRound).map { i ->
                val kind = i % 5 // 0 ACK, 1 ERR, 2 no reply (timeout), 3 cancelled mid-wait, 4 JSON ACK
                kind to scope.async {
                    if (kind == 4) session.awaitJson("json $round/$i", 8000).let { it.first to (it.second?.optString("out") ?: "") }
                    else session.await("cmd $round/$i", 8000)
                }
            }
            clock.runCurrent()
            val frames = wire.sent.takeLast(perRound)
            assertEquals(perRound * (round + 1), wire.sent.size)
            val ids = frames.map { it.getInt("id") }
            assertEquals(perRound, ids.toSet().size)
            maxPending = maxOf(maxPending, session.pendingCount())
            assertEquals(perRound, session.pendingCount())

            calls.forEach { (kind, call) -> if (kind == 3) call.cancel() }
            clock.runCurrent()
            assertEquals(perRound - perRound / 5, session.pendingCount())

            val replies = ArrayList<ByteArray>()
            calls.forEachIndexed { i, (kind, _) ->
                when (kind) {
                    0 -> replies += ackFrame(ids[i], true, "ok-${ids[i]}")
                    1 -> replies += errFrame(ids[i], "err-${ids[i]}")
                    3 -> replies += ackFrame(ids[i], true, "late-${ids[i]}")
                    4 -> replies += ackFrame(ids[i], true, "json-${ids[i]}")
                }
                // Event flood interleaved with replies, some reusing an in-flight id.
                repeat(3) { e -> replies += eventFrame(if (e == 0) ids[i] else 0, i, random.nextInt(0, 300)) }
            }
            replies.shuffle(random)
            wire.deliver(replies, if (round % 2 == 0) random else null)
            reader.pump()
            totalEvents += perRound * 3
            clock.runCurrent()

            // Only the never-answered commands remain, still bound to their virtual deadline.
            assertEquals(perRound / 5, session.pendingCount())
            clock.advanceBy(7999)
            calls.filter { it.first == 2 }.forEach { assertTrue(it.second.isActive) }
            clock.advanceBy(1)

            // Late replies after timeout must be ignored.
            wire.deliver(calls.indices.filter { calls[it].first == 2 }.map { ackFrame(ids[it], true, "late") }, random)
            reader.pump()
            clock.runCurrent()

            calls.forEachIndexed { i, (kind, call) ->
                if (kind == 3) { assertTrue(call.isCancelled); return@forEachIndexed }
                val expected = when (kind) {
                    0 -> true to "ok-${ids[i]}"
                    1 -> false to "err-${ids[i]}"
                    2 -> false to NO_RESPONSE
                    else -> true to "json-${ids[i]}"
                }
                assertEquals("round $round call $i", expected, call.completedValue())
            }
            assertFalse(session.hasPending)
            assertEquals(0, session.pendingCount())
            assertEquals(0, clock.timerCount)
            assertEquals(0, scope.childCount())
            assertEquals(0, bufferedBytes(client))
        }
        assertEquals(perRound, maxPending)
        assertEquals(totalEvents, reader.events)
        assertTrue(reader.maxBuffer <= BUFFER_BOUND)
        scope.cancel()
    }

    @Test(timeout = 60_000)
    fun eventFloodIsLosslessForValidFramesWithABoundedBufferAndCannotCompleteCommands() {
        val clock = VirtualClock()
        val wire = VirtualWire()
        val client = CompanionClient(wire)
        val session = CommandSession(client, wire)
        val reader = Reader(client, wire) { session.route(it) }
        val scope = CoroutineScope(clock + Job())
        val call = scope.async { session.await("long_op", 600_000) }
        clock.runCurrent()
        val id = wire.sent.single().getInt("id")

        val random = Random(42)
        val prefix = "{\"v\":0,\"t\":6,\"id\":$id,\"d\":{\"ev\":\"flood\",\"pad\":\""
        val maxFrame = rawFrame((prefix + "x".repeat(8192 - prefix.length - 3) + "\"}}").toByteArray(Charsets.UTF_8))
        assertEquals(4 + 8192, maxFrame.size)
        val malformed = rawFrame("{\"v\":0,\"t\":6,".toByteArray(Charsets.UTF_8))
        var valid = 0
        var dropped = 0
        repeat(10) { batch ->
            val frames = ArrayList<ByteArray>()
            repeat(2000) { n ->
                when {
                    n % 500 == 499 -> { frames += malformed; dropped++ }
                    n % 100 == 0 -> { frames += maxFrame; valid++ }
                    n % 7 == 0 -> { frames += frame(Kind.DATA, id, JSONObject().put("seq", n)); valid++ }
                    else -> { frames += eventFrame(id, n, random.nextInt(0, 400)); valid++ }
                }
            }
            wire.deliver(frames, if (batch % 2 == 0) null else random, maxChunk = 1500)
            reader.pump()
            clock.runCurrent()
            assertTrue(call.isActive)
            assertEquals(1, session.pendingCount())
        }
        // Current policy: well-formed frames are never dropped by the client (the transport is only
        // read when no complete frame is buffered); malformed payloads are skipped without a counter.
        assertEquals(valid, reader.events)
        assertEquals(0, reader.replies)
        assertTrue("buffer peaked at ${reader.maxBuffer}", reader.maxBuffer <= BUFFER_BOUND)
        assertEquals(0, bufferedBytes(client))

        wire.deliver(listOf(eventFrame(id, 0), ackFrame(id, true, "done"), eventFrame(id, 1)), random, maxChunk = 3)
        reader.pump()
        clock.runCurrent()
        assertEquals(true to "done", call.completedValue())
        assertFalse(session.hasPending)

        // A corrupt length prefix mid-flood is skipped as noise; the decoder resyncs on the next frame.
        wire.deliver(listOf(eventFrame(id, 2), byteArrayOf(0, 0, 0, 0x7F), eventFrame(id, 3)), null)
        assertEquals(2, client.readMessage(1)?.getJSONObject("d")?.getInt("n"))
        assertEquals(3, client.readMessage(1)?.getJSONObject("d")?.getInt("n"))
        assertEquals(0, bufferedBytes(client))
        assertEquals(0, clock.timerCount)
        assertEquals(0, scope.childCount())
    }

    /** Device reply policy for one SUB frame. */
    private enum class Reply { OK, REJECT, SILENT, WRITE_FAIL }

    private class StreamFixture(seed: Int) {
        val clock = VirtualClock()
        val scope = CoroutineScope(clock + Job())
        val random = Random(seed)
        var wire = VirtualWire()
        var client = CompanionClient(wire)
        var session: CommandSession? = CommandSession(client, wire)
        var reader = Reader(client, wire) { session?.route(it) }
        val caps = DeviceCaps(wifi = true, ble = true, cc1101 = true, mic = true)
        val states = HashMap<String, Boolean>()
        val errors = ArrayList<String>()
        private var nextLog = 0L
        val started = HashSet<Long>()
        val finished = HashMap<Long, Int>()
        /** Policy chosen per (stream, on) before the frame is written; consumed on write. */
        var policy: (String, Boolean) -> Reply = { _, _ -> Reply.OK }
        val outbox = ArrayList<ByteArray>()
        private val decided = HashMap<Int, Reply>()
        val subs = StreamSubscriptions(scope, { session }, { caps },
            onState = { s, on -> states[s] = on },
            onError = { _, text -> errors += text },
            onStart = { _, _ -> (++nextLog).also { started += it } },
            onFinish = { log, _, _ -> finished[log] = (finished[log] ?: 0) + 1 },
            dispatcher = clock, timeoutMs = 8000)

        init { bind() }

        fun bind() {
            val w = wire
            w.failWrite = { env -> env.getInt("t") == Kind.SUB && decide(env) == Reply.WRITE_FAIL }
            w.onFrame = { env ->
                if (env.getInt("t") == Kind.SUB) {
                    val id = env.getInt("id")
                    when (decided.remove(id) ?: Reply.OK) {
                        Reply.OK -> outbox += ackFrame(id, true, "ok")
                        Reply.REJECT -> outbox += if (id % 2 == 0) ackFrame(id, false, "rejected") else errFrame(id, "rejected")
                        else -> Unit
                    }
                }
            }
        }

        private fun decide(env: JSONObject): Reply {
            val d = env.getJSONObject("d")
            return policy(d.getString("stream"), d.getBoolean("on")).also { decided[env.getInt("id")] = it }
        }

        /** Write queued SUB frames, deliver replies (shuffled, fragmented), then expire deadlines. */
        fun settle(beforeDeadline: () -> Unit = {}) {
            clock.runCurrent()
            outbox.shuffle(random)
            wire.deliver(outbox.toList(), if (random.nextBoolean()) random else null)
            outbox.clear()
            reader.pump()
            clock.runCurrent()
            beforeDeadline()
            clock.advanceBy(8000)
            clock.runCurrent()
        }

        fun assertDrained() {
            val s = session!!
            assertFalse(s.hasPending)
            assertEquals(0, s.pendingCount())
            assertEquals(0, subs.mapSize("pending"))
            assertEquals(0, clock.timerCount)
            assertEquals(0, scope.childCount())
            assertEquals(0, bufferedBytes(client))
            assertEquals(started, finished.keys)
            assertTrue(finished.values.all { it == 1 })
            for ((stream, on) in states) assertEquals(stream, stream in subs.activeStreams(), on)
            assertTrue(subs.cleanupStreams().containsAll(subs.activeStreams()))
        }

        fun reconnect() {
            wire = VirtualWire()
            client = CompanionClient(wire)
            session = CommandSession(client, wire)
            reader = Reader(client, wire) { session?.route(it) }
            bind()
        }
    }

    @Test(timeout = 60_000)
    fun longStreamSessionMatchesOwnershipModelAndKeepsStateBounded() {
        val f = StreamFixture(7)
        val streams = StreamPolicy.streams.sorted()
        // Reference model of the documented ownership rules (all outcomes are removals, so the
        // order in which replies arrive does not matter).
        val active = HashSet<String>()
        val cleanup = HashSet<String>()
        class Req(val stream: String, val on: Boolean, val reply: Reply) { var cancelled = false }
        val replies = listOf(Reply.OK, Reply.OK, Reply.OK, Reply.OK, Reply.OK, Reply.OK, Reply.REJECT, Reply.SILENT, Reply.WRITE_FAIL)
        // Requests in write order; a request superseded before dispatch is never written.
        val unwritten = ArrayDeque<Req>()
        f.policy = { s, on ->
            val req = unwritten.removeFirst()
            assertEquals(req.stream to req.on, s to on)
            req.reply
        }
        repeat(3000) { step ->
            val reqs = ArrayList<Req>()
            val pendingByStream = HashMap<String, Req>()
            fun supersede(req: Req?) { if (req != null) { req.cancelled = true; unwritten.remove(req) } }
            repeat(if (f.random.nextInt(4) == 0) 2 else 1) {
                val stream = streams[f.random.nextInt(streams.size)]
                val on = f.random.nextBoolean()
                val req = Req(stream, on, replies[f.random.nextInt(replies.size)])
                supersede(pendingByStream.remove(stream))
                if (on) StreamPolicy.exclusivePeers(stream).forEach { peer ->
                    supersede(pendingByStream.remove(peer))
                    active.remove(peer)
                }
                if (on) { active += stream; cleanup += stream } else active -= stream
                pendingByStream[stream] = req
                reqs += req
                unwritten.addLast(req)
                assertTrue(f.subs.set(stream, on))
            }
            f.settle {
                // Answered, failed and superseded requests release their deadline and job at once;
                // only a written request whose ACK is withheld may still hold one.
                val waiting = reqs.count { !it.cancelled && it.reply == Reply.SILENT }
                assertEquals("step $step", waiting, f.clock.timerCount)
                assertEquals("step $step", waiting, f.scope.childCount())
                assertEquals("step $step", waiting, f.session!!.pendingCount())
            }
            assertTrue(unwritten.isEmpty())
            for (req in reqs) {
                if (req.cancelled) continue
                if (req.reply == Reply.OK) {
                    if (req.on) cleanup.removeAll(StreamPolicy.exclusivePeers(req.stream)) else cleanup.remove(req.stream)
                } else if (req.on) active.remove(req.stream)
            }
            assertEquals("step $step", active, f.subs.activeStreams())
            assertEquals("step $step", cleanup, f.subs.cleanupStreams())
            f.assertDrained()
        }
        f.policy = { _, _ -> Reply.OK }
        f.subs.stopAll()
        f.settle()
        assertTrue(f.subs.activeStreams().isEmpty())
        assertTrue(f.subs.cleanupStreams().isEmpty())
        f.assertDrained()
        f.scope.cancel()
    }

    @Test(timeout = 60_000)
    fun stopAllRetainsCleanupForRejectedMissingAndUnwrittenOffsAcrossManyCycles() {
        val f = StreamFixture(11)
        val on = listOf("wifi.ap", "ble.scan", "subghz.raw", "mic.pcm")
        val offPolicy = mapOf("wifi.ap" to Reply.OK, "ble.scan" to Reply.REJECT,
            "subghz.raw" to Reply.SILENT, "mic.pcm" to Reply.WRITE_FAIL)
        repeat(500) { cycle ->
            f.errors.clear()
            f.policy = { s, isOn -> if (isOn) (if (s == "mic.pcm" && cycle % 2 == 1) Reply.SILENT else Reply.OK) else offPolicy.getValue(s) }
            on.forEach { assertTrue(f.subs.set(it, true)) }
            f.settle()
            // A start whose ACK never arrived is inactive but still owed an OFF.
            val expectedActive = if (cycle % 2 == 1) on.toSet() - "mic.pcm" else on.toSet()
            assertEquals(expectedActive, f.subs.activeStreams())
            assertEquals(on.toSet(), f.subs.cleanupStreams())

            f.subs.stopAll()
            assertTrue(f.subs.activeStreams().isEmpty())
            f.settle()
            assertEquals(setOf("ble.scan", "subghz.raw", "mic.pcm"), f.subs.cleanupStreams())
            assertTrue(f.errors.containsAll(listOf("rejected", NO_STREAM_ACK, "stall")))
            f.assertDrained()

            if (cycle % 3 == 0) {
                // Disconnected Stop: nothing can be written, every obligation survives for detach.
                val s = f.session!!
                s.close("Disconnected")
                f.subs.stopAll()
                assertEquals(setOf("ble.scan", "subghz.raw", "mic.pcm"), f.subs.detach())
                assertTrue(f.subs.cleanupStreams().isEmpty())
                f.reconnect()
            } else {
                f.policy = { _, _ -> Reply.OK }
                f.subs.stopAll()
                f.settle()
                assertTrue(f.subs.cleanupStreams().isEmpty())
            }
            f.assertDrained()
        }
        f.scope.cancel()
    }

    @Test(timeout = 60_000)
    fun reconnectIsolatesResidualCallbacksAndLateFramesFromTheOldSession() {
        val f = StreamFixture(3)
        val legacy = ArrayList<Pair<Boolean, String>>()
        repeat(300) { cycle ->
            val old = f.session!!
            val oldWire = f.wire
            val oldClient = f.client
            f.policy = { s, _ -> if (s == "mic.pcm") Reply.SILENT else Reply.OK }
            assertTrue(f.subs.set("ble.scan", true))
            f.clock.runCurrent()
            f.wire.deliver(f.outbox.toList(), f.random); f.outbox.clear()
            f.reader.pump()
            f.clock.runCurrent()
            assertTrue(f.subs.set("mic.pcm", true))
            f.clock.runCurrent() // mic.pcm SUB written, ACK withheld
            val text = f.scope.async { old.await("info", 8000) }
            val json = f.scope.async { old.awaitJson("fs_list /", 8000) }
            val legacyId = old.nextId()
            old.expect(legacyId) { ok, out -> legacy += ok to out }
            old.transmit("storage list /", legacyId)
            f.clock.runCurrent()
            val oldIds = oldWire.sent.map { it.getInt("id") }

            // Disconnect, alternating the order of session close and stream detach.
            if (cycle % 2 == 0) {
                val owed = f.subs.detach()
                assertEquals(setOf("ble.scan", "mic.pcm"), owed)
                // Detach alone releases the withheld SUB; only the three commands remain for close().
                assertEquals(0, f.subs.mapSize("pending"))
                assertEquals(3, old.pendingCount())
                // As in disconnect(): best-effort OFF on the old link; its ACK arrives as a residual frame.
                owed.forEach { oldClient.sub(it, false) }
                f.session = null
                oldWire.close()
                old.close("Disconnected")
            } else {
                oldWire.close()
                old.close("Disconnected") // residual SUB callback fails mic.pcm first
                assertEquals(setOf("ble.scan", "mic.pcm"), f.subs.detach())
                f.session = null
            }
            f.clock.runCurrent()
            assertEquals(false to "Disconnected", text.completedValue())
            assertEquals("Disconnected", json.completedValue().second?.getString("msg"))
            assertEquals(List(cycle + 1) { false to "Disconnected" }, legacy)
            assertEquals(0, old.pendingCount())
            assertTrue(f.subs.activeStreams().isEmpty())

            f.reconnect()
            val fresh = f.session!!
            val next = f.scope.async { fresh.await("info", 8000) }
            f.clock.runCurrent()
            val nextId = f.wire.sent.single().getInt("id")
            assertFalse(nextId in oldIds)

            // Residual old-session replies reach the new reader and the old (closed) session.
            val late = oldIds.map { ackFrame(it, true, "stale") } + errFrame(legacyId, "stale") + f.outbox
            f.outbox.clear()
            f.wire.deliver(late.shuffled(f.random), f.random)
            f.reader.pump()
            assertEquals("", old.route(JSONObject().put("t", Kind.ACK).put("id", oldIds.first())
                .put("d", JSONObject().put("ok", true).put("out", "stale"))))
            f.clock.runCurrent()
            assertTrue(next.isActive)
            assertEquals(1, fresh.pendingCount())
            assertTrue(f.subs.activeStreams().isEmpty())
            assertTrue(f.subs.cleanupStreams().isEmpty())

            f.wire.deliver(listOf(ackFrame(nextId, true, "fresh")), f.random)
            f.reader.pump()
            f.clock.runCurrent()
            assertEquals(true to "fresh", next.completedValue())
            f.clock.advanceBy(8000) // any leftover old deadline would fire here
            assertTrue(f.subs.activeStreams().isEmpty())
            f.assertDrained()
        }
        f.scope.cancel()
    }

    @Test(timeout = 10_000)
    fun commandAndStreamDeadlinesFollowVirtualTimeExactly() {
        val f = StreamFixture(5)
        val s = f.session!!
        val call = f.scope.async { s.await("info", 8000) }
        f.policy = { _, _ -> Reply.SILENT }
        assertTrue(f.subs.set("ble.scan", true))
        f.clock.runCurrent()
        val start = f.clock.now
        f.clock.advanceBy(7999)
        assertTrue(call.isActive)
        assertEquals(setOf("ble.scan"), f.subs.activeStreams())
        // A reply in the final millisecond wins the race against the deadline.
        val subId = f.wire.sent.first { it.getInt("t") == Kind.SUB }.getInt("id")
        f.wire.deliver(listOf(ackFrame(subId, true, "ok")), null)
        f.reader.pump()
        f.clock.advanceBy(1)
        assertEquals(8000, f.clock.now - start)
        assertEquals(false to NO_RESPONSE, call.completedValue())
        assertEquals(setOf("ble.scan"), f.subs.activeStreams())
        assertEquals(setOf("ble.scan"), f.subs.cleanupStreams())
        assertNull(f.errors.firstOrNull())
        f.assertDrained()
        f.scope.cancel()
    }
}

@Suppress("OPT_IN_USAGE")
private fun <T> Deferred<T>.completedValue(): T {
    assertTrue("still running", isCompleted)
    return getCompleted()
}
