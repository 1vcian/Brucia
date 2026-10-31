package app.brucia

import app.brucia.monitor.JamDetectService
import app.brucia.session.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Reuses [GateRunner] and [until] from DeviceOperationTest. */
class JamDetectServiceTest {
    private val runner = GateRunner()
    @Volatile private var connected = true

    private fun CoroutineScope.svc(r: CommandRunner = runner) = JamDetectService(this, r, { connected })
    private fun JamDetectService.state() = status.value to busy.value
    private suspend fun JamDetectService.result(reply: Pair<Boolean, String>, call: JamDetectService.() -> Unit): String {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 && !busy.value }
        return status.value
    }

    @Test(timeout = 5_000) fun runningTextResultMappingAndTimeout() = runBlocking {
        runner.sticky = true; val svc = svc()
        svc.run(5)
        assertEquals("Listening for 5s..." to true, svc.state())
        until { runner.calls.size == 1 }
        assertEquals("jam_detect 5" to 13_000L, runner.calls[0])
        runner.reply(0, true, ""); until { !svc.busy.value }
        assertEquals("Done.", svc.status.value)
        runner.sticky = false
        assertEquals("deauth=3 total=9", svc.result(true to "deauth=3 total=9") { run(10) })
        assertEquals("Failed.", svc.result(false to "") { run(10) })
        assertEquals("busy", svc.result(false to "busy") { run(10) })
        assertEquals(listOf("jam_detect 5", "jam_detect 10", "jam_detect 10", "jam_detect 10"), runner.cmds)
        assertEquals(18_000L, JamDetectService.timeoutMsFor(10))
    }

    @Test(timeout = 5_000) fun callWhileBusyIsIgnored() = runBlocking {
        val svc = svc() // non-sticky: the cancellable await frees on stop()
        svc.run(5); until { runner.calls.size == 1 }
        svc.run(5) // ignored while busy (the VM's `if (_jamDetectActive) return`)
        delay(20); assertEquals(1, runner.calls.size); svc.stop()
    }

    @Test(timeout = 5_000) fun notConnectedRejectsWithoutSending() = runBlocking {
        connected = false
        val svc = svc(); svc.run(5)
        assertEquals("Connect the device over USB first." to false, svc.state())
        delay(20); assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun stopSupersedesWaitingRun() = runBlocking {
        runner.sticky = true; val svc = svc()
        svc.run(5); until { runner.calls.size == 1 }
        svc.stop(); runner.reply(0, true, "late"); delay(20)
        assertEquals("Stopped." to false, svc.state())
    }
}
