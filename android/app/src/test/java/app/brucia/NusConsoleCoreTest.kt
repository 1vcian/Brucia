package app.brucia

import app.brucia.ble.BleConsole.State
import app.brucia.ble.NusConsoleCore
import app.brucia.ble.NusLink
import app.brucia.ble.NusProtocol
import app.brucia.ble.NusSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F39: BLE console session isolation, on the JVM. A fake link stands in for one BluetoothGatt; the
 * test delivers its callbacks (tagged with the session they belong to) at chosen moments on a
 * virtual clock, so late callbacks of an old connection and timeouts are deterministic.
 */
class NusConsoleCoreTest {
    private class FakeLink(val session: NusSession) : NusLink {
        var bonded = true
        var bondStarts = true
        var writeStarts = true
        var mtuRequests = 0
        var cccdWrites = 0
        val writes = ArrayList<String>()
        var closed = false
        /** Calls that reached this link after it was closed: an old session touching the radio. */
        val afterClose = ArrayList<String>()

        private fun touch(what: String) { if (closed) afterClose += what }
        override fun isBonded() = bonded.also { touch("isBonded") }
        override fun startBond() = bondStarts.also { touch("startBond") }
        override fun requestMtu(mtu: Int): Boolean { touch("requestMtu"); mtuRequests++; return true }
        override fun discoverServices(): Boolean { touch("discoverServices"); return true }
        override fun resolveNus(): Boolean { touch("resolveNus"); return true }
        override fun enableNotifications(): Boolean { touch("enableNotifications"); cccdWrites++; return true }
        override fun write(chunk: ByteArray): Boolean {
            touch("write")
            if (!writeStarts) return false
            writes += chunk.toString(Charsets.UTF_8)
            return true
        }
        override fun close() { closed = true }
    }

    private class Rig {
        val clock = VirtualClock()
        val scope = CoroutineScope(clock + Job())
        val core = NusConsoleCore(scope)
        val links = ArrayList<FakeLink>()

        fun open(name: String): FakeLink {
            core.connect(name) { s -> FakeLink(s).also { links += it } }
            clock.runCurrent()
            return links.last()
        }

        /** Connected, bonded, MTU [mtu], services found; CCCD write started and waiting for its ACK. */
        fun untilSubscribe(name: String, mtu: Int = 247): FakeLink {
            val l = open(name)
            core.onConnectionStateChange(l.session, connected = true, success = true, status = 0)
            core.onMtuChanged(l.session, mtu, success = true)
            core.onServicesDiscovered(l.session, success = true)
            clock.runCurrent()
            assertEquals(1, l.cccdWrites)
            return l
        }

        fun ready(name: String, mtu: Int = 247): FakeLink {
            val l = untilSubscribe(name, mtu)
            core.onDescriptorWrite(l.session, NusProtocol.CCCD, success = true)
            clock.runCurrent()
            assertEquals(State.Ready(name), core.state.value)
            return l
        }

        fun ack(l: FakeLink, ok: Boolean = true) {
            core.onCharacteristicWrite(l.session, NusProtocol.RX, ok)
            clock.runCurrent()
        }

        fun send(text: String) { assertNull(core.send(text)); clock.runCurrent() }

        fun close() { scope.cancel(); clock.runCurrent() }
    }

    private val writeFailed = "(write failed: is the phone still paired with this device?)"

    @Test(timeout = 5_000)
    fun lateNotificationOfOldSessionIsIgnored() {
        val r = Rig()
        val a = r.ready("A")
        r.ready("B")
        r.core.onNotification(a.session, NusProtocol.TX, "stale from A\n".toByteArray())
        r.clock.runCurrent()
        assertFalse(r.core.log.value, "stale from A" in r.core.log.value)
        r.close()
    }

    @Test(timeout = 5_000)
    fun lateWriteAckOfOldSessionDoesNotCompleteTheNewWrite() {
        val r = Rig()
        val a = r.ready("A")
        r.send("info")
        assertEquals(listOf("info\n"), a.writes)
        val b = r.ready("B")
        r.send("help")
        r.send("free")
        assertEquals(listOf("help\n"), b.writes)
        r.ack(a) // A's ACK arrives late, while B's first write is still in flight
        assertEquals("B must still wait for its own ACK", listOf("help\n"), b.writes)
        r.ack(b)
        assertEquals(listOf("help\n", "free\n"), b.writes)
        r.close()
    }

    @Test(timeout = 5_000)
    fun lateDescriptorAckOfOldSessionDoesNotMarkTheNewOneReady() {
        val r = Rig()
        val a = r.untilSubscribe("A")
        r.untilSubscribe("B")
        r.core.onDescriptorWrite(a.session, NusProtocol.CCCD, success = true)
        r.clock.runCurrent()
        assertEquals(State.Connecting("B"), r.core.state.value)
        assertFalse(r.core.log.value.contains("Connected to"))
        r.close()
    }

    @Test(timeout = 5_000)
    fun disconnectDuringSubscribeLeavesIdleWithoutReadyOrFailure() {
        val r = Rig()
        val a = r.untilSubscribe("A")
        r.core.disconnect()
        r.clock.runCurrent()
        assertEquals(State.Idle, r.core.state.value)
        assertFalse(r.core.log.value.contains("Connected to"))
        r.core.onDescriptorWrite(a.session, NusProtocol.CCCD, success = true) // ACK after disconnect
        r.clock.runCurrent()
        assertEquals(State.Idle, r.core.state.value)
        assertTrue(a.closed)
        assertEquals(emptyList<String>(), a.afterClose)
        r.close()
    }

    @Test(timeout = 5_000)
    fun subscribeOfOldSessionDoesNotFailTheNewConnection() {
        val r = Rig()
        val a = r.untilSubscribe("A")
        val b = r.open("B") // reconnect while A waits for its CCCD ACK
        assertEquals(State.Connecting("B"), r.core.state.value)
        assertFalse("B must not be closed by A's aborted subscribe", b.closed)
        r.clock.advanceBy(20_000) // past A's subscribe timeout
        assertEquals(State.Connecting("B"), r.core.state.value)
        assertFalse(b.closed)
        assertTrue(a.closed)
        r.close()
    }

    @Test(timeout = 5_000)
    fun doubleConnectKeepsOnlyTheLatestSession() {
        val r = Rig()
        val a = r.open("A")
        val b = r.open("B")
        assertTrue(a.closed)
        // A's stack still reports its connection, a disconnect and a cancelled bond: all ignored.
        r.core.onConnectionStateChange(a.session, connected = true, success = true, status = 0)
        r.core.onMtuChanged(a.session, 100, success = true)
        r.core.onConnectionStateChange(a.session, connected = false, success = false, status = 8)
        r.core.onBondState(a.session, bonded = false)
        r.core.onBondState(a.session, bonded = true)
        r.clock.runCurrent()
        assertEquals(State.Connecting("B"), r.core.state.value)
        assertFalse(b.closed)
        assertEquals(0, b.mtuRequests)
        assertEquals(emptyList<String>(), a.afterClose)
        // B then completes normally, with its own MTU.
        r.core.onConnectionStateChange(b.session, connected = true, success = true, status = 0)
        r.core.onMtuChanged(b.session, 23, success = true)
        r.core.onServicesDiscovered(b.session, success = true)
        r.clock.runCurrent()
        r.core.onDescriptorWrite(b.session, NusProtocol.CCCD, success = true)
        r.clock.runCurrent()
        assertEquals(State.Ready("B"), r.core.state.value)
        r.send("x".repeat(30)) // 31 bytes on MTU 23: two chunks
        assertEquals(1, b.writes.size)
        assertEquals(20, b.writes[0].length)
        r.close()
    }

    @Test(timeout = 5_000)
    fun chunkTimeoutStopsTheRestOfTheLineAndReportsIt() {
        val r = Rig()
        val a = r.ready("A", mtu = 23)
        r.send("x".repeat(50)) // 51 bytes: chunks of 20, 20, 11
        r.send("info")
        r.ack(a)
        assertEquals(2, a.writes.size)
        r.clock.advanceBy(10_000) // second chunk never acknowledged
        assertEquals("no chunk after the failed one, and no later line", 2, a.writes.size)
        assertTrue(r.core.log.value, writeFailed in r.core.log.value)
        // Part of the line may sit in the firmware buffer: the next line would join it, so the
        // session ends (the firmware clears its buffer on disconnect) and says why.
        val s = r.core.state.value
        assertTrue("$s", s is State.Failed)
        assertTrue(a.closed)
        assertEquals(emptyList<String>(), a.afterClose)
        r.close()
    }

    @Test(timeout = 5_000)
    fun chunkWriteFailureStopsTheRestOfTheLine() {
        val r = Rig()
        val a = r.ready("A", mtu = 23)
        r.send("y".repeat(50))
        r.ack(a)
        r.ack(a, ok = false) // second chunk rejected by the stack
        assertEquals(2, a.writes.size)
        assertTrue(r.core.log.value, writeFailed in r.core.log.value)
        assertTrue(r.core.state.value is State.Failed)
        r.close()
    }

    @Test(timeout = 5_000)
    fun firstChunkRejectedDropsTheLineButKeepsTheSession() {
        val r = Rig()
        val a = r.ready("A", mtu = 23)
        r.send("z".repeat(50))
        r.ack(a, ok = false) // nothing of the line reached the device (e.g. not paired)
        assertEquals(1, a.writes.size)
        assertTrue(r.core.log.value, writeFailed in r.core.log.value)
        assertEquals(State.Ready("A"), r.core.state.value)
        r.send("info")
        assertEquals("info\n", a.writes.last())
        r.ack(a)
        assertEquals(1, Regex(Regex.escape(writeFailed)).findAll(r.core.log.value).count())
        r.close()
    }

    @Test(timeout = 5_000)
    fun writeThatCannotStartMidLineStopsTheRest() {
        val r = Rig()
        val a = r.ready("A", mtu = 23)
        r.send("w".repeat(50))
        a.writeStarts = false
        r.ack(a)
        assertEquals(1, a.writes.size)
        assertTrue(r.core.log.value, writeFailed in r.core.log.value)
        assertTrue(r.core.state.value is State.Failed)
        r.close()
    }

    @Test(timeout = 5_000)
    fun retryAfterFailureWorksOnAFreshSession() {
        val r = Rig()
        val a = r.ready("A", mtu = 23)
        r.send("x".repeat(50))
        r.ack(a)
        r.clock.advanceBy(10_000)
        assertTrue(r.core.state.value is State.Failed)
        val failures = Regex(Regex.escape(writeFailed)).findAll(r.core.log.value).count()
        val b = r.ready("A")
        r.send("info")
        assertEquals(listOf("info\n"), b.writes)
        r.ack(a) // A's very late ACK: not B's
        r.ack(b)
        r.clock.advanceBy(30_000)
        assertEquals(State.Ready("A"), r.core.state.value)
        assertEquals(failures, Regex(Regex.escape(writeFailed)).findAll(r.core.log.value).count())
        r.core.onNotification(b.session, NusProtocol.TX, "heap 1234\n".toByteArray())
        r.clock.runCurrent()
        assertTrue(r.core.log.value.endsWith("heap 1234\n"))
        r.close()
    }

    @Test(timeout = 5_000)
    fun disconnectCancelsEveryJobAndWaitOfTheSession() {
        val r = Rig()
        val a = r.ready("A", mtu = 23)
        r.send("x".repeat(50))
        r.send("info")
        assertEquals(1, a.writes.size)
        assertTrue(r.clock.timerCount > 0) // the write timeout is pending
        r.core.disconnect()
        r.clock.runCurrent()
        assertEquals("no timeout of the old session left", 0, r.clock.timerCount)
        r.clock.advanceBy(60_000)
        r.ack(a)
        assertEquals(1, a.writes.size)
        assertEquals(State.Idle, r.core.state.value)
        assertFalse(r.core.log.value.contains(writeFailed))
        assertEquals(emptyList<String>(), a.afterClose)
        r.close()
    }

    @Test(timeout = 5_000)
    fun bondResultOfTheCurrentSessionStillDrivesSetup() {
        val r = Rig()
        r.core.connect("A") { s -> FakeLink(s).apply { bonded = false }.also { r.links += it } }
        val a = r.links.last()
        r.core.onConnectionStateChange(a.session, connected = true, success = true, status = 0)
        assertEquals(State.Pairing("A"), r.core.state.value)
        r.core.onBondState(a.session, bonded = true)
        assertEquals(1, a.mtuRequests)
        r.core.onBondState(a.session, bonded = false)
        assertEquals(State.Failed("Pairing failed or cancelled. Enter the PIN shown on the device."), r.core.state.value)
        r.close()
    }
}
