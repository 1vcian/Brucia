package app.brucia

import app.brucia.monitor.WidsService
import app.brucia.session.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Reuses [GateRunner] and [until] from DeviceOperationTest. Non-sticky runner so stop() (which
 *  cancels the loop) frees any pending await and runBlocking does not hang. */
class WidsServiceTest {
    @Test(timeout = 5000) fun cancelledParentAndImmediateClosedLinkDoNotLeaveMonitorActive() {
        for (cancelled in listOf(true, false)) {
            val parent = Job().apply { if (cancelled) cancel() }
            var calls = 0
            val svc = WidsService(CoroutineScope(parent + kotlinx.coroutines.Dispatchers.Unconfined),
                CommandRunner { _, _ -> calls++; true to "" }, { true }, { false })
            svc.start()
            assertFalse(svc.active.value)
            assertEquals(if (cancelled) WidsService.STOPPED else WidsService.LINK_CLOSED, svc.status.value)
            assertEquals(0, calls)
            parent.cancel()
        }
    }

    @Test(timeout = 5000) fun invalidWindowOrThresholdDoesNotSendOrWait() = runBlocking {
        val svc = svc()
        for ((seconds, threshold) in listOf(Int.MIN_VALUE to 1, 0 to 1, 121 to 1, Int.MAX_VALUE to 1, 15 to 0)) {
            svc.start(seconds, threshold)
            assertFalse(svc.active.value)
            assertTrue(svc.status.value.contains("Window must"))
        }
        assertTrue(runner.calls.isEmpty())
    }

    private val runner = GateRunner()
    @Volatile private var connected = true
    @Volatile private var linkOpen = true

    private fun CoroutineScope.svc(r: CommandRunner = runner) = WidsService(this, r, { connected }, { linkOpen })
    private fun WidsService.state() = status.value to active.value
    private suspend fun answer(i: Int, ok: Boolean, out: String) {
        until { runner.calls.size == i + 1 }; runner.reply(i, ok, out)
    }

    /** Records every status emission; a per-window result line is transient (overwritten by the
     *  next "Monitoring" line), so it is only observable in the emission history, not by polling. */
    private fun CoroutineScope.record(svc: WidsService): Pair<CopyOnWriteArrayList<String>, Job> {
        val seen = CopyOnWriteArrayList<String>()
        return seen to launch(start = CoroutineStart.UNDISPATCHED) { svc.status.collect { seen += it } }
    }

    @Test(timeout = 5_000) fun windowStatusParsingAlertAndEvents() = runBlocking {
        val svc = svc(); val (seen, col) = record(svc)
        svc.start(windowSecs = 2, threshold = 20); until { runner.calls.size == 1 }
        assertEquals("jam_detect 2" to 10_000L, runner.calls[0])
        answer(0, true, "deauth=25 total=100") // over threshold -> ALERT + event
        until { svc.events.value.size == 1 }
        svc.events.value[0].let { assertEquals(Triple(25, 100, true), Triple(it.deauth, it.total, it.alert)) }
        answer(1, true, "deauth=3 total=10") // under threshold, deauth>0 -> event
        until { svc.events.value.size == 2 }
        answer(2, true, "deauth=0 total=5") // no deauth -> no event
        until { runner.calls.size == 4 }
        assertEquals(2, svc.events.value.size)
        assertTrue(runner.cmds.all { it == "jam_detect 2" })
        svc.stop(); col.cancel()
        assertTrue(seen.containsAll(listOf(
            "Monitoring (window 1, 2s)...", "Monitoring (window 2, 2s)...",
            "ALERT: deauth flood (25 in 2s)", "Clear (window 2, deauth=3)", "Clear (window 3, deauth=0)")))
    }

    @Test(timeout = 5_000) fun failedWindowKeepsMonitoring() = runBlocking {
        val svc = svc(); val (seen, col) = record(svc)
        svc.start(windowSecs = 1, threshold = 20)
        answer(0, false, ""); until { runner.calls.size == 2 } // blank failure keeps looping
        answer(1, false, "radio busy"); until { runner.calls.size == 3 } // non-blank failure keeps looping
        assertTrue(svc.active.value); svc.stop(); col.cancel()
        assertTrue(seen.containsAll(listOf("jam_detect failed", "radio busy")))
    }

    @Test(timeout = 5_000) fun notConnectedAtStartSendsNothing() = runBlocking {
        connected = false
        val svc = svc(); svc.start()
        assertEquals("Connect the device over USB first." to false, svc.state())
        delay(20); assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun endsWhenLinkClosesNoSpin() = runBlocking {
        val svc = svc()
        svc.start(windowSecs = 2, threshold = 20); until { runner.calls.size == 1 }
        linkOpen = false
        runner.reply(0, true, "deauth=0 total=0") // loop resumes, re-checks link, exits
        until { !svc.active.value }; delay(30)
        assertEquals("Monitor stopped (link closed).", svc.status.value)
        assertEquals(1, runner.calls.size) // bounded: no spin after the link closed
    }

    @Test(timeout = 5_000) fun endsWhenDisconnectedNoSpin() = runBlocking {
        val svc = svc()
        svc.start(windowSecs = 2, threshold = 20); until { runner.calls.size == 1 }
        connected = false // the bug: VM would spin on immediate "Not connected." failures
        runner.reply(0, false, "Not connected.")
        until { !svc.active.value }
        delay(30); assertTrue(runner.calls.size <= 2) // bounded, not a tight spin
        assertEquals("Monitor stopped (link closed).", svc.status.value)
    }

    @Test(timeout = 5_000) fun stopThenRestartIsOwnedByNewRun() = runBlocking {
        val svc = svc(); val (seen, col) = record(svc)
        svc.start(windowSecs = 2, threshold = 20); until { runner.calls.size == 1 }
        svc.stop()
        assertEquals("Monitor stopped." to false, svc.state())
        svc.start(windowSecs = 2, threshold = 20) // restart: events cleared, superseded run must not write
        assertEquals("Monitoring (window 1, 2s)..." to true, svc.state())
        assertTrue(svc.events.value.isEmpty())
        until { runner.calls.size == 2 }
        runner.reply(0, true, "deauth=99 total=99") // stale reply from the stopped run
        delay(20)
        answer(1, true, "deauth=5 total=5")
        until { svc.events.value.size == 1 }
        assertEquals(5, svc.events.value[0].deauth) // the stale deauth=99 never landed
        until { runner.calls.size == 3 }
        svc.stop(); col.cancel()
        assertTrue(seen.contains("Clear (window 1, deauth=5)"))
        assertFalse(seen.any { it.contains("99") }) // the stale run never wrote status
    }

    @Test(timeout = 5_000) fun ignoredWhileActive() = runBlocking {
        val svc = svc()
        svc.start(windowSecs = 2, threshold = 20); until { runner.calls.size == 1 }
        svc.start(windowSecs = 5, threshold = 1); delay(20) // ignored while active
        assertEquals("Monitoring (window 1, 2s)..." to true, svc.state())
        assertEquals(1, runner.calls.size); svc.stop()
    }

    @Test(timeout = 5_000) fun eventListCapsAt100AndClearEvents() = runBlocking {
        val svc = svc()
        svc.start(windowSecs = 1, threshold = 10_000) // never alerts; deauth>0 -> one event per window
        for (i in 0 until 105) answer(i, true, "deauth=1 total=1")
        until { svc.events.value.size == 100 }
        delay(20); assertEquals(100, svc.events.value.size) // capped at the last 100
        svc.stop(); svc.clearEvents(); assertTrue(svc.events.value.isEmpty())
    }
}
