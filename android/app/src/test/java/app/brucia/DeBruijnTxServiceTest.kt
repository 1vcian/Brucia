package app.brucia

import app.brucia.subghz.DeBruijnTx
import app.brucia.subghz.DeBruijnTxService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

/** Reuses [GateRunner] and [until] from DeviceOperationTest. */
class DeBruijnTxServiceTest {
    private val freq = 433_920_000L
    private val runner = GateRunner()
    @Volatile private var connected = true

    private fun CoroutineScope.svc() = DeBruijnTxService(this, runner, { connected })

    /** A small multi-chunk plan (order 4, te 2000, tight frame -> 3 overlapping bursts). */
    private fun plan() = (DeBruijnTx.plan(4, 2000, freq, 1, 380) as DeBruijnTx.Plan.Ready)
        .also { assertEquals("test needs 3 chunks", 3, it.chunksPerPass) }

    @Test(timeout = 5_000) fun streamsExactSequenceAndHonestSummary() = runVirtual {
        runner.auto = true to "tx ok"
        val p = plan()
        val svc = svc()
        val immediate = svc.start(p, "433.92 MHz")
        assertEquals("Transmitting De Bruijn sweep: burst 0/3 on 433.92 MHz...", immediate)
        until { !svc.active.value }
        assertEquals(p.commands, runner.cmds.toList())
        assertEquals(
            "De Bruijn B(2,4) sweep sent: 3/3 bursts on 433.92 MHz (1 pass, 19 bits/pass).",
            svc.status.value,
        )
        assertFalse(svc.active.value)
    }

    @Test(timeout = 5_000) fun progressAdvancesPerBurst() = runVirtual {
        val svc = svc(); svc.start(plan(), "F")
        until { runner.calls.size == 1 }
        assertEquals("Transmitting De Bruijn sweep: burst 0/3 on F...", svc.status.value)
        assertTrue(svc.active.value)
        runner.reply(0, true, "ok"); until { runner.calls.size == 2 }
        assertEquals("Transmitting De Bruijn sweep: burst 1/3 on F...", svc.status.value)
        svc.stop()
    }

    @Test(timeout = 5_000) fun stopWhileBurstInFlightIsHonestAndSendsNothingMore() = runVirtual {
        val p = plan(); val svc = svc(); svc.start(p, "F")
        until { runner.calls.size == 1 }
        runner.reply(0, true, "ok")
        until { runner.calls.size == 2 } // burst 2 dispatched to the device, awaiting its ACK (on air)
        svc.stop()
        runner.reply(1, true, "late") // late ACK ignored
        delay(50)
        assertEquals(2, runner.calls.size) // burst 3 was never sent (later bursts cancelled)
        // The in-flight burst cannot be recalled: the status must not claim the sweep is stopped.
        assertEquals(DeBruijnTxService.STOPPED_IN_FLIGHT, svc.status.value)
        assertNotEquals(DeBruijnTxService.STOPPED, DeBruijnTxService.STOPPED_IN_FLIGHT)
        assertFalse(svc.active.value)
    }

    @Test(timeout = 5_000) fun disconnectMidStreamStopsWithRealReason() = runVirtual {
        val svc = svc(); svc.start(plan(), "433.92 MHz")
        until { runner.calls.size == 1 }
        runner.reply(0, true, "ok")
        until { runner.calls.size == 2 }
        runner.reply(1, false, "Connection closed or changed.")
        until { !svc.active.value }
        assertEquals(2, runner.calls.size) // halted; burst 3 not sent
        assertTrue(svc.status.value.startsWith("De Bruijn sweep stopped at burst 2/3 on 433.92 MHz:"))
        assertTrue(svc.status.value.contains("Connection closed or changed."))
    }

    @Test(timeout = 5_000) fun notConnectedSendsNothing() = runVirtual {
        connected = false
        val svc = svc()
        val msg = svc.start(plan(), "F")
        assertEquals(DeBruijnTxService.NOT_CONNECTED, msg)
        delay(50)
        assertTrue(runner.calls.isEmpty())
        assertFalse(svc.active.value)
        assertEquals(DeBruijnTxService.NOT_CONNECTED, svc.status.value)
    }

    @Test(timeout = 5_000) fun showSurfacesRejectionWithoutSending() = runVirtual {
        val svc = svc()
        svc.show("De Bruijn transmit order 22 is too large. Nothing was transmitted.")
        delay(50)
        assertTrue(runner.calls.isEmpty())
        assertFalse(svc.active.value)
        assertEquals("De Bruijn transmit order 22 is too large. Nothing was transmitted.", svc.status.value)
    }
}
