package app.brucia

import app.brucia.transport.FlashPortOwner
import app.brucia.transport.PortLease
import app.brucia.transport.UsbPermissionRequests
import app.brucia.transport.UsbPermissionRequests.Outcome
import app.brucia.transport.launchFlashOwned
import app.brucia.transport.releaseUsbPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UsbOwnershipTest {

    private class FakeClock(var now: Long = 1_000L) { fun read() = now }

    private class FakePort {
        var closes = 0
        fun lease() = PortLease { closes++ }
    }

    @Test fun leaseClosesUnderlyingPortExactlyOnce() {
        val port = FakePort()
        val lease = port.lease()
        assertTrue(lease.close())
        assertFalse(lease.close())
        assertFalse(lease.close())
        assertEquals(1, port.closes)
        assertTrue(lease.isClosed)
    }

    @Test fun throwingCloserStillCountsAsReleased() {
        var calls = 0
        val lease = PortLease { calls++; error("usb gone") }
        assertTrue(lease.close())
        assertFalse(lease.close())
        assertEquals(1, calls)
    }

    @Test fun jobCancelledBeforeStartStillReleasesPort() = runBlocking {
        val owner = FlashPortOwner()
        val token = owner.begin(60_000)!!
        val port = FakePort()
        assertEquals(FlashPortOwner.Adopt.ADOPTED, owner.adopt(token, port.lease()))
        // A cleared ViewModel: its scope is already cancelled when the flash is launched.
        val deadScope = CoroutineScope(Job().apply { cancel() })
        val bodyRan = java.util.concurrent.atomic.AtomicBoolean(false)
        val abandoned = CompletableDeferred<Unit>()
        val job = deadScope.launchFlashOwned(owner, token, Dispatchers.Default, onAbandoned = { abandoned.complete(Unit) }) {
            bodyRan.set(true)
        }
        job.join()
        // Completion handlers may still be running on the dispatcher thread when join() returns.
        withTimeout(5_000) { abandoned.await() }
        assertFalse("a job cancelled before start must not run its body", bodyRan.get())
        assertEquals(1, port.closes)
        assertNull(owner.activeToken)
    }

    @Test fun cancellationAfterBodyStartsReleasesOnceViaBody() = runBlocking {
        val owner = FlashPortOwner()
        val token = owner.begin(60_000)!!
        val port = FakePort()
        owner.adopt(token, port.lease())
        val started = CompletableDeferred<Unit>()
        var abandoned = 0
        val job = CoroutineScope(Job()).launchFlashOwned(owner, token, Dispatchers.Default, onAbandoned = { abandoned++ }) {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, port.closes)
        assertEquals("the body's own release path ended it, not the completion handler", 0, abandoned)
        assertNull(owner.activeToken)
    }

    @Test fun normalCompletionReleasesOnceAndAllowsNextOperation() = runBlocking {
        val owner = FlashPortOwner()
        val token = owner.begin(60_000)!!
        assertNull("only one operation at a time", owner.begin(60_000))
        val port = FakePort()
        owner.adopt(token, port.lease())
        CoroutineScope(Job()).launchFlashOwned(owner, token, Dispatchers.Default) { }.join()
        assertEquals(1, port.closes)
        val next = owner.begin(60_000)
        assertNotNull(next)
        assertTrue(next != token)
    }

    @Test fun staleTokenPortIsClosedAndCurrentOperationUntouched() {
        val owner = FlashPortOwner()
        val old = owner.begin(60_000)!!
        assertTrue(owner.finish(old))
        val current = owner.begin(60_000)!!
        val live = FakePort()
        assertEquals(FlashPortOwner.Adopt.ADOPTED, owner.adopt(current, live.lease()))
        val late = FakePort()
        assertEquals(FlashPortOwner.Adopt.STALE, owner.adopt(old, late.lease()))
        assertEquals(1, late.closes)
        assertEquals(0, live.closes)
        assertFalse("a stale finish must not end the current operation", owner.finish(old))
        assertEquals(current, owner.activeToken)
    }

    @Test fun secondPortForSameOperationIsClosedFirstStaysHeld() {
        val owner = FlashPortOwner()
        val token = owner.begin(60_000)!!
        val first = FakePort()
        val second = FakePort()
        assertEquals(FlashPortOwner.Adopt.ADOPTED, owner.adopt(token, first.lease()))
        assertEquals(FlashPortOwner.Adopt.DUPLICATE, owner.adopt(token, second.lease()))
        assertEquals(1, second.closes)
        assertEquals(0, first.closes)
        owner.finish(token)
        assertEquals(1, first.closes)
    }

    @Test fun handoffDeadlineEndsOperationAndRejectsLatePort() {
        val clock = FakeClock()
        val owner = FlashPortOwner(clock::read)
        val token = owner.begin(90_000)!!
        clock.now += 89_999
        assertFalse("not due yet", owner.expireHandoff(token))
        clock.now += 2
        val late = FakePort()
        assertEquals(FlashPortOwner.Adopt.EXPIRED, owner.adopt(token, late.lease()))
        assertEquals(1, late.closes)
        assertNull(owner.activeToken)
        assertFalse("already ended", owner.expireHandoff(token))
    }

    @Test fun deadlineTimerDoesNotEndAnAdoptedOperation() {
        val clock = FakeClock()
        val owner = FlashPortOwner(clock::read)
        val token = owner.begin(90_000)!!
        val port = FakePort()
        owner.adopt(token, port.lease())
        clock.now += 200_000
        assertFalse(owner.expireHandoff(token))
        assertEquals(token, owner.activeToken)
        assertEquals(0, port.closes)
    }

    @Test fun closedOwnerReleasesHeldPortAndRejectsEverythingAfter() {
        val owner = FlashPortOwner()
        val token = owner.begin(60_000)!!
        val held = FakePort()
        owner.adopt(token, held.lease())
        owner.close()
        assertEquals(1, held.closes)
        val late = FakePort()
        assertEquals(FlashPortOwner.Adopt.CLOSED, owner.adopt(token, late.lease()))
        assertEquals(1, late.closes)
        assertNull(owner.begin(60_000))
        assertFalse(owner.finish(token))
        assertEquals(1, held.closes)
    }

    @Test fun permissionResultCorrelatedByIdAndDevice() {
        val requests = UsbPermissionRequests<String>(FakeClock()::read)
        val companion = requests.begin("/dev/bus/usb/001/002", 60_000, "companion")
        val rom = requests.begin("/dev/bus/usb/001/003", 60_000, "rom")
        // Grant naming another device under the companion id: ignored, request stays pending.
        val cross = requests.resolve(companion, "/dev/bus/usb/001/003", true)
        assertEquals(Outcome.WRONG_DEVICE, cross.outcome)
        assertNull(cross.payload)
        assertEquals(Outcome.WRONG_DEVICE, requests.resolve(rom, null, true).outcome)
        val granted = requests.resolve(rom, "/dev/bus/usb/001/003", true)
        assertEquals(Outcome.GRANTED, granted.outcome)
        assertSame("rom", granted.payload)
        // The same result delivered twice is stale.
        assertEquals(Outcome.STALE, requests.resolve(rom, "/dev/bus/usb/001/003", true).outcome)
        assertEquals(Outcome.STALE, requests.resolve(-1, "/dev/bus/usb/001/002", true).outcome)
        val denied = requests.resolve(companion, "/dev/bus/usb/001/002", false)
        assertEquals(Outcome.DENIED, denied.outcome)
        assertEquals("companion", denied.payload)
        assertEquals(0, requests.size)
    }

    @Test fun permissionTimesOutOnceAndLateGrantIsStale() {
        val clock = FakeClock()
        val requests = UsbPermissionRequests<String>(clock::read)
        val id = requests.begin("dev", 60_000, "flash")
        clock.now += 59_999
        assertNull("not due yet", requests.expire(id))
        clock.now += 1
        assertEquals("flash", requests.expire(id))
        assertNull("settles once", requests.expire(id))
        assertEquals(Outcome.STALE, requests.resolve(id, "dev", true).outcome)
    }

    @Test fun grantArrivingAfterDeadlineBeforeTimerIsTimedOut() {
        val clock = FakeClock()
        val requests = UsbPermissionRequests<String>(clock::read)
        val id = requests.begin("dev", 60_000, "flash")
        clock.now += 60_001
        val late = requests.resolve(id, "dev", true)
        assertEquals(Outcome.TIMED_OUT, late.outcome)
        assertEquals("flash", late.payload)
        assertNull("timer finds nothing left to settle", requests.expire(id))
    }

    @Test fun drainSettlesEveryPendingRequestAndIdsNeverRepeat() {
        val a = UsbPermissionRequests<String>()
        val b = UsbPermissionRequests<String>()
        val first = a.begin("dev", 60_000, "x")
        val second = b.begin("dev", 60_000, "y")
        assertTrue("ids are process-wide so a recreated owner cannot match an old request", first != second)
        assertEquals(Outcome.STALE, b.resolve(first, "dev", true).outcome)
        assertEquals(listOf("x"), a.drain())
        assertEquals(0, a.size)
        assertEquals(Outcome.STALE, a.resolve(first, "dev", true).outcome)
    }

    // --- Companion link vs flash arbitration (one owner of the USB device at a time) ---

    private class Ends {
        val log = ArrayList<Pair<Long, FlashPortOwner.Kind>>()
        fun record(token: Long, kind: FlashPortOwner.Kind) { synchronized(log) { log += token to kind } }
    }

    @Test fun companionOpenRefusedWhileFlashHoldsDeviceEvenPastItsDeadline() {
        val clock = FakeClock()
        val owner = FlashPortOwner(clock::read)
        val flash = owner.begin(90_000)!!
        assertNull("attach auto-connect / manual Connect during a flash", owner.beginCompanion(10_000))
        clock.now += 200_000
        // The flash's own deadline timer must observe its end; a companion open never reaps it.
        assertNull(owner.beginCompanion(10_000))
        assertNull("a second flash neither", owner.begin(90_000))
        assertEquals(flash, owner.flashToken)
        assertTrue(owner.expireHandoff(flash))
        assertNotNull(owner.beginCompanion(10_000))
    }

    @Test fun flashPreemptsCompanionAndClosesItsPortOnce() {
        val ends = Ends()
        val owner = FlashPortOwner(onEnded = ends::record)
        val link = owner.beginCompanion(10_000)!!
        val companionPort = FakePort()
        assertEquals(FlashPortOwner.Adopt.ADOPTED, owner.adopt(link, companionPort.lease()))
        assertEquals(FlashPortOwner.Kind.COMPANION, owner.holder)
        assertNull("the companion is not a flash", owner.flashToken)
        val flash = owner.begin(90_000)!!
        assertEquals(1, companionPort.closes)
        assertEquals(FlashPortOwner.Kind.FLASH, owner.holder)
        assertEquals(flash, owner.flashToken)
        assertEquals(listOf(link to FlashPortOwner.Kind.COMPANION), ends.log)
        // The old link's late teardown cannot end the flash or reopen anything.
        assertFalse(owner.finish(link))
        val late = FakePort()
        assertEquals(FlashPortOwner.Adopt.STALE, owner.adopt(link, late.lease()))
        assertEquals(1, late.closes)
        assertEquals(flash, owner.flashToken)
        assertEquals(1, companionPort.closes)
    }

    @Test fun secondCompanionOpenRefusedUntilFirstReleases() {
        val owner = FlashPortOwner()
        val first = owner.beginCompanion(10_000)!!
        assertNull("auto-connect racing a manual Connect", owner.beginCompanion(10_000))
        val port = FakePort()
        owner.adopt(first, port.lease())
        assertNull("linked: still one owner", owner.beginCompanion(10_000))
        assertTrue(owner.finish(first))
        assertEquals(1, port.closes)
        val second = owner.beginCompanion(10_000)
        assertNotNull(second)
        assertTrue(second != first)
    }

    @Test fun abandonedCompanionOpenExpiresLazilyAndItsLatePortIsClosed() {
        val clock = FakeClock()
        val ends = Ends()
        val owner = FlashPortOwner(clock::read, ends::record)
        val lost = owner.beginCompanion(10_000)!!
        clock.now += 9_999
        assertNull("not due yet", owner.beginCompanion(10_000))
        clock.now += 1
        val next = owner.beginCompanion(10_000)!!
        assertEquals(listOf(lost to FlashPortOwner.Kind.COMPANION), ends.log)
        val late = FakePort()
        assertEquals(FlashPortOwner.Adopt.STALE, owner.adopt(lost, late.lease()))
        assertEquals(1, late.closes)
        assertEquals(next, owner.activeToken)
        // An adopted link never expires; a flash still preempts it.
        val port = FakePort()
        owner.adopt(next, port.lease())
        clock.now += 1_000_000
        assertNull(owner.beginCompanion(10_000))
        assertNotNull(owner.begin(90_000))
        assertEquals(1, port.closes)
    }

    @Test fun everyFlashEndPathReportsTheEndExactlyOnce() = runBlocking {
        val clock = FakeClock()
        val ends = Ends()
        val owner = FlashPortOwner(clock::read, ends::record)
        // finish (normal end / abort)
        val a = owner.begin(90_000)!!
        assertTrue(owner.finish(a)); assertFalse(owner.finish(a))
        // deadline timer
        val b = owner.begin(90_000)!!
        clock.now += 90_000
        assertTrue(owner.expireHandoff(b)); assertFalse(owner.expireHandoff(b))
        // late handoff after the deadline
        val c = owner.begin(90_000)!!
        clock.now += 90_001
        assertEquals(FlashPortOwner.Adopt.EXPIRED, owner.adopt(c, FakePort().lease()))
        assertFalse(owner.finish(c))
        // job cancelled before its body started
        val d = owner.begin(90_000)!!
        owner.adopt(d, FakePort().lease())
        val abandoned = CompletableDeferred<Unit>()
        CoroutineScope(Job().apply { cancel() })
            .launchFlashOwned(owner, d, Dispatchers.Default, onAbandoned = { abandoned.complete(Unit) }) { }
            .join()
        withTimeout(5_000) { abandoned.await() }
        // owner shutdown (ViewModel cleared)
        val e = owner.begin(90_000)!!
        owner.close()
        owner.close()
        val flash = FlashPortOwner.Kind.FLASH
        assertEquals(listOf(a to flash, b to flash, c to flash, d to flash, e to flash), ends.log)
    }

    @Test fun throwingEndListenerDoesNotSkipThePortRelease() {
        val owner = FlashPortOwner(onEnded = { _, _ -> error("service unavailable") })
        val token = owner.begin(90_000)!!
        val port = FakePort()
        owner.adopt(token, port.lease())
        assertTrue(owner.finish(token))
        assertEquals(1, port.closes)
        assertNull(owner.activeToken)
    }

    // --- usb-serial-for-android 3.8.1 release rule (D4/D5) ---

    /**
     * Model of CommonUsbSerialPort 3.8.1: open() refuses "Already open" before taking the
     * connection (lines 114-115) and close() closes the connection it was opened with (line 155).
     */
    private class LibPort {
        var connection: Conn? = null
        fun open(c: Conn) { if (connection != null) throw IOException("Already open"); connection = c }
        fun close() { val c = connection ?: throw IOException("Already closed"); c.close(); connection = null }
    }
    private class Conn { var closes = 0; fun close() { closes++ } }

    @Test fun refusedSecondOpenReleasesOnlyItsOwnConnection() {
        val port = LibPort()
        val live = Conn()
        port.open(live)
        val second = Conn()
        var openedHere = false
        try { port.open(second); openedHere = true } catch (_: IOException) {
            releaseUsbPort(openedHere, closePort = { port.close() }, closeConnection = { second.close() })
        }
        assertEquals(1, second.closes)
        assertEquals("the live transport keeps its port and connection", 0, live.closes)
        assertSame(live, port.connection)
    }

    @Test fun openedPortIsReleasedByClosingThePortOnly() {
        val port = LibPort()
        val conn = Conn()
        port.open(conn)
        var direct = 0
        releaseUsbPort(true, closePort = { port.close() }, closeConnection = { direct++ })
        assertEquals("port.close() closes the connection", 1, conn.closes)
        assertEquals(0, direct)
        assertNull(port.connection)
    }
}
