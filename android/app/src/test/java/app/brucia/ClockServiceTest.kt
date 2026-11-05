package app.brucia

import app.brucia.device.ClockService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClockServiceTest {
    private val runner = GateRunner()

    private suspend fun ClockService.result(reply: Pair<Boolean, String>, call: ClockService.() -> Unit): String {
        runner.auto = reply
        call()
        until { !busy.value }
        return status.value
    }

    @Test fun getAndSetTextsAndCommands() = runVirtual {
        val svc = ClockService(this, runner, { true })
        val iso = "2026-10-30T12:00:00Z"
        assertEquals(iso, svc.result(true to iso) { get(); assertEquals("Reading clock..." to true, status.value to busy.value) })
        assertEquals("(no time)", svc.result(true to "") { get() })
        assertEquals("Clock read failed.", svc.result(false to "") { get() })
        assertEquals("Clock synced.", svc.result(true to "") { set(1_793_000_000L); assertEquals("Syncing clock...", status.value) })
        assertEquals("Clock sync failed.", svc.result(false to "") { set(1_000_000_000L) })
        assertEquals(iso, svc.result(true to iso) { set(1_793_000_000L) })
        assertEquals(listOf("clock_get", "clock_get", "clock_get", "clock_set 1793000000",
            "clock_set 1000000000", "clock_set 1793000000"), runner.cmds)
    }

    @Test fun implausibleEpochIsNotSentAndConnectionComesFirst() = runVirtual {
        var connected = false
        val svc = ClockService(this, runner, { connected })
        svc.set(1L)
        assertEquals("Connect the device over USB first.", svc.status.value)
        connected = true
        svc.set(999_999_999L)
        assertEquals("implausible epoch" to false, svc.status.value to svc.busy.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }
}
