package app.brucia

import app.brucia.session.CommandRunner
import app.brucia.subghz.SubGhzBruteforceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Reuses [GateRunner] and [until] from DeviceOperationTest. */
class SubGhzBruteforceServiceTest {
    @Test(timeout = 5000) fun invalidDurationsAreRejectedAndCannotOverflowTimeouts() = runBlocking {
        val svc = svc()
        for (seconds in listOf(Int.MIN_VALUE, -9, 0, 31, Int.MAX_VALUE)) {
            svc.run(2, 433920000L, 24, seconds)
            assertFalse(svc.busy.value)
            assertTrue(svc.status.value.contains("Duration must"))
            assertTrue(SubGhzBruteforceService.timeoutMsFor(seconds) in 9_000L..38_000L)
        }
        assertTrue(runner.calls.isEmpty())
    }

    private val runner = GateRunner()
    @Volatile private var connected = true

    private fun CoroutineScope.svc(r: CommandRunner = runner) = SubGhzBruteforceService(this, r, { connected })
    private fun SubGhzBruteforceService.state() = status.value to busy.value
    private suspend fun SubGhzBruteforceService.result(reply: Pair<Boolean, String>, call: SubGhzBruteforceService.() -> Unit): String {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 && !busy.value }
        return status.value
    }

    @Test(timeout = 5_000) fun runningTextCommandMappingAndTimeout() = runBlocking {
        runner.sticky = true; val svc = svc()
        svc.run(protocolIdx = 2, freqHz = 433920000L, bits = 24, secs = 10)
        assertEquals("Brute-forcing for 10s..." to true, svc.state())
        until { runner.calls.size == 1 }
        assertEquals("subghz_bruteforce 2 433920000 24 10" to 18_000L, runner.calls[0])
        runner.reply(0, true, ""); until { !svc.busy.value }
        assertEquals("Done.", svc.status.value)
        runner.sticky = false
        assertEquals("sent 42 codes", svc.result(true to "sent 42 codes") { run(1, 315000000L, 12, 5) })
        assertEquals("Failed.", svc.result(false to "") { run(1, 315000000L, 12, 5) })
        assertEquals("no cc1101", svc.result(false to "no cc1101") { run(1, 315000000L, 12, 5) })
        assertEquals("subghz_bruteforce 1 315000000 12 5", runner.cmds[1])
        assertEquals(13_000L, SubGhzBruteforceService.timeoutMsFor(5))
    }

    @Test(timeout = 5_000) fun callWhileBusyIsIgnored() = runBlocking {
        val svc = svc() // non-sticky: the cancellable await frees on stop()
        svc.run(2, 433920000L, 24, 10); until { runner.calls.size == 1 }
        svc.run(2, 433920000L, 24, 10) // ignored while busy (the VM's `if (_subBruteActive) return`)
        delay(20); assertEquals(1, runner.calls.size); svc.stop()
    }

    @Test(timeout = 5_000) fun notConnectedRejectsWithoutSending() = runBlocking {
        connected = false
        val svc = svc(); svc.run(2, 433920000L, 24, 10)
        assertEquals("Connect the device over USB first." to false, svc.state())
        delay(20); assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun stopSupersedesWaitingRun() = runBlocking {
        runner.sticky = true; val svc = svc()
        svc.run(2, 433920000L, 24, 10); until { runner.calls.size == 1 }
        svc.stop(); runner.reply(0, true, "late"); delay(20)
        assertEquals("Stopped." to false, svc.state())
    }
}
