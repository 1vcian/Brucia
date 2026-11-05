package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.protocol.StreamPolicy
import app.brucia.session.CommandSession
import app.brucia.session.StreamSubscriptions
import app.brucia.transport.Transport
import app.brucia.wardrive.DeviceCaps
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class StreamSubscriptionsTest {
    private class Wire : Transport {
        val frames = mutableListOf<JSONObject>()
        var reply: (JSONObject) -> Unit = {}
        var fail = false
        override var isOpen = true
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
        override fun write(bytes: ByteArray) {
            if (fail) throw java.io.IOException("broken pipe")
            val frame = JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))
            frames.add(frame)
            reply(frame)
        }
    }
    private class Queue : CoroutineDispatcher() {
        val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher = scope.virtualUnconfined) {
        val wire = Wire()
        var caps = DeviceCaps(wifi = true, ble = true, cc1101 = true, mic = true)
        val first = CommandSession(CompanionClient(wire), wire)
        var session: CommandSession? = first
        val states = mutableMapOf<String, Boolean>()
        val errors = mutableListOf<String>()
        val results = mutableListOf<Pair<Boolean, String>>()
        val streams = StreamSubscriptions(scope, { session }, { caps },
            onState = { stream, on -> states[stream] = on },
            onError = { _, text -> errors.add(text) }, onFinish = { _, ok, out -> results.add(ok to out) },
            dispatcher = dispatcher, timeoutMs = 30)
        fun ack(index: Int, ok: Boolean = true, target: CommandSession = first) {
            target.route(JSONObject().put("t", Kind.ACK).put("id", wire.frames[index].getInt("id"))
                .put("d", JSONObject().put("ok", ok).put("out", if (ok) "ok" else "rejected")))
        }
        fun autoAck() { wire.reply = { ack(wire.frames.lastIndex, target = session!!) } }
    }

    @Test(timeout = 5000) fun absentHardwareAndUnknownStreamsNeverWrite() = runVirtual<Unit> {
        val f = Fixture(this)
        f.caps = DeviceCaps()
        for (stream in StreamPolicy.streams) assertFalse(f.streams.set(stream, true))
        assertFalse(f.streams.set("wifi.unknown", false))
        assertTrue(f.wire.frames.isEmpty())
        assertTrue(f.streams.cleanupStreams().isEmpty())
    }

    @Test(timeout = 5000) fun synchronousAckAndExclusiveReplacementReleaseEveryRequest() = runVirtual<Unit> {
        val f = Fixture(this); f.autoAck()
        f.streams.set("wifi.ap", true)
        assertEquals(setOf("wifi.ap"), f.streams.activeStreams())
        f.streams.set("wifi.monitor", true, 6, "00:11:22:33:44:55")
        assertEquals(setOf("wifi.monitor"), f.streams.activeStreams())
        assertEquals(setOf("wifi.monitor"), f.streams.cleanupStreams())
        assertFalse(f.first.hasPending)
        val body = f.wire.frames.last().getJSONObject("d")
        assertEquals(6, body.getInt("channel"))
        assertEquals("00:11:22:33:44:55", body.getString("bssid"))
        f.streams.stopAll()
        assertTrue(f.streams.cleanupStreams().isEmpty())
        assertTrue(f.streams.activeStreams().isEmpty())
        assertEquals(3, f.results.size)
    }

    @Test(timeout = 5000) fun stopSupersedesPendingStartAndLateAckCannotReactivateIt() = runVirtual<Unit> {
        val f = Fixture(this)
        f.streams.set("mic.pcm", true)
        f.streams.set("mic.pcm", false)
        f.ack(0)
        assertEquals(false, f.states["mic.pcm"])
        assertTrue(f.first.hasPending) // only the OFF remains
        f.ack(1)
        assertFalse(f.first.hasPending)
        assertTrue(f.streams.cleanupStreams().isEmpty())
        assertEquals(2, f.results.size)
    }

    @Test(timeout = 5000) fun missingAckAndRejectedStopRemainEligibleForCleanup() = runVirtual<Unit> {
        val f = Fixture(this)
        f.streams.set("wifi.ap", true)
        until { !f.first.hasPending }
        assertTrue(f.streams.activeStreams().isEmpty())
        assertEquals(setOf("wifi.ap"), f.streams.cleanupStreams())
        f.streams.stopAll()
        f.ack(1, false)
        assertEquals(setOf("wifi.ap"), f.streams.cleanupStreams())
        f.autoAck()
        f.streams.stopAll()
        assertTrue(f.streams.cleanupStreams().isEmpty())
        assertEquals(3, f.wire.frames.size)
    }

    @Test(timeout = 5000) fun disconnectAndReconnectIsolateLateReplies() = runVirtual<Unit> {
        val f = Fixture(this)
        f.streams.set("ble.scan", true)
        assertEquals(setOf("ble.scan"), f.streams.detach())
        assertFalse(f.first.hasPending)
        f.session = CommandSession(CompanionClient(f.wire), f.wire)
        f.streams.set("ble.scan", true)
        f.ack(0, false)
        assertEquals(true, f.states["ble.scan"])
        f.ack(1, target = f.session!!)
        assertEquals(setOf("ble.scan"), f.streams.activeStreams())
        f.streams.detach()
    }

    @Test(timeout = 5000) fun queuedRequestsRecheckCapabilitiesAndSessionBeforeWriting() = runVirtual<Unit> {
        for (changeCaps in listOf(true, false)) {
            val queue = Queue()
            val f = Fixture(this, queue)
            f.streams.set("wifi.ap", true)
            if (changeCaps) f.caps = DeviceCaps() else f.session = null
            queue.drain()
            assertTrue(f.wire.frames.isEmpty())
            assertEquals(false, f.states["wifi.ap"])
            assertFalse(f.first.hasPending)
            f.streams.detach()
        }
    }

    @Test(timeout = 5000) fun parentCancelledBeforeDispatchCannotLeaveActiveOrPendingRequests() = runVirtual<Unit> {
        val parent = Job()
        val queue = Queue()
        val f = Fixture(CoroutineScope(parent), queue)
        f.streams.set("mic.pcm", true)
        parent.cancel()
        queue.drain()
        assertTrue(f.wire.frames.isEmpty())
        assertTrue(f.streams.activeStreams().isEmpty())
        assertFalse(f.first.hasPending)
        assertFalse(f.streams.set("mic.pcm", true))
        f.streams.detach()
    }

    @Test(timeout = 5000) fun invalidMonitorReplacementPreservesExistingStream() = runVirtual<Unit> {
        val f = Fixture(this); f.autoAck()
        f.streams.set("wifi.ap", true)
        assertFalse(f.streams.set("wifi.monitor", true, 0))
        assertFalse(f.streams.set("wifi.monitor", true, 14))
        assertFalse(f.streams.set("wifi.monitor", true, 6, "invalid"))
        assertFalse(f.streams.set("ble.scan", true, 6))
        assertEquals(setOf("wifi.ap"), f.streams.activeStreams())
        assertEquals(1, f.wire.frames.size)
        f.streams.detach()
    }

    @Test(timeout = 5000) fun exclusivePeersSupersedeButIndependentStreamsKeepTheirOwnership() = runVirtual<Unit> {
        val f = Fixture(this)
        f.streams.set("wifi.ap", true)
        f.streams.set("ble.scan", true)
        f.streams.set("wifi.probe", true)
        f.ack(0, false)
        assertEquals(setOf("ble.scan", "wifi.probe"), f.streams.activeStreams())
        f.ack(2)
        f.ack(1)
        assertFalse(f.first.hasPending)
        assertEquals(setOf("ble.scan", "wifi.probe"), f.streams.cleanupStreams())
        f.streams.detach()
    }

    @Test(timeout = 5000) fun failedWriteAndClosedSessionTerminateWithoutLeakingWaits() = runVirtual<Unit> {
        val f = Fixture(this)
        f.wire.fail = true
        f.streams.set("mic.pcm", true)
        assertFalse(f.first.hasPending)
        assertTrue(f.streams.activeStreams().isEmpty())
        assertTrue(f.errors.contains("broken pipe"))
        f.wire.fail = false
        f.streams.set("ble.scan", true)
        f.first.close()
        assertFalse(f.first.hasPending)
        assertEquals(false, f.states["ble.scan"])
        assertFalse(f.streams.set("wifi.ap", true))
        f.streams.detach()
    }
}
