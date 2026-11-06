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
    @Test(timeout = 5000) fun invalidDurationsAreRejectedAndCannotOverflowTimeouts() = runVirtual {
        val svc = svc()
        for (seconds in listOf(Int.MIN_VALUE, -9, 0, 121, Int.MAX_VALUE)) {
            svc.run(seconds)
            assertFalse(svc.busy.value)
            assertTrue(svc.status.value.contains("Duration must"))
            assertTrue(JamDetectService.timeoutMsFor(seconds) in 9_000L..128_000L)
        }
        assertTrue(runner.calls.isEmpty())
    }

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

    @Test(timeout = 5_000) fun runningTextResultMappingAndTimeout() = runVirtual {
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

    @Test(timeout = 5_000) fun callWhileBusyIsIgnored() = runVirtual {
        val svc = svc() // non-sticky: the cancellable await frees on stop()
        svc.run(5); until { runner.calls.size == 1 }
        svc.run(5) // ignored while busy (the VM's `if (_jamDetectActive) return`)
        delay(20); assertEquals(1, runner.calls.size); svc.stop()
    }

    @Test(timeout = 5_000) fun notConnectedRejectsWithoutSending() = runVirtual {
        connected = false
        val svc = svc(); svc.run(5)
        assertEquals("Connect the device over USB first." to false, svc.state())
        delay(20); assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun stopSupersedesWaitingRun() = runVirtual {
        runner.sticky = true; val svc = svc()
        svc.run(5); until { runner.calls.size == 1 }
        svc.stop(); runner.reply(0, true, "late"); delay(20)
        assertEquals("Stopped." to false, svc.state())
    }

    /** Stop all: the device gets its polled stop and the window ends with the device's reply. */
    @Test(timeout = 5_000) fun requestStopSendsDeviceStopAndKeepsWaitingForTheReply() = runVirtual {
        runner.sticky = true
        val stopsSent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val svc = JamDetectService(this, runner, { connected }, stopRunner = { cmd, _ -> stopsSent += cmd; true to "" })
        svc.requestStop() // idle: nothing sent
        settle()
        assertTrue(stopsSent.isEmpty())
        svc.run(120)
        until { runner.calls.size == 1 }
        svc.requestStop()
        until { stopsSent.isNotEmpty() }
        assertEquals(listOf(JamDetectService.STOP_CMD), stopsSent)
        assertTrue(svc.busy.value) // still owned until the device answers
        runner.reply(0, true, "deauth=0 total=4")
        until { !svc.busy.value }
        assertEquals("deauth=0 total=4", svc.status.value)
        connected = false
        svc.run(5) // rejected, and a disconnected requestStop sends nothing
        svc.requestStop()
        settle()
        assertEquals(1, stopsSent.size)
    }
}
