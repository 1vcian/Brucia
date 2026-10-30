package app.brucia

import app.brucia.ble.BadBleService
import app.brucia.session.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BadBleServiceTest {
    private val runner = GateRunner()
    @Volatile private var connected = true
    private val notConnected = "Connect the device over USB first."

    private fun CoroutineScope.svc(r: CommandRunner = runner) = BadBleService(this, r, { connected })
    private fun BadBleService.state() = status.value to busy.value

    private suspend fun BadBleService.result(reply: Pair<Boolean, String>, call: BadBleService.() -> Unit): String {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 && !busy.value }
        return status.value
    }

    private suspend fun BadBleService.rejects(text: String, call: BadBleService.() -> Unit) {
        val n = runner.calls.size
        call()
        assertEquals(text to false, state())
        delay(20)
        assertEquals(n, runner.calls.size)
    }

    @Test fun runTextsAndTimeout() = runBlocking {
        val svc = svc()
        svc.run("STRING hi")
        assertEquals("Running over BLE..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Done.", svc.status.value)
        assertEquals("ran 2 lines", svc.result(true to "ran 2 lines") { run("a") })
        assertEquals("Failed.", svc.result(false to "") { run("b") })
        assertEquals("ble hid init failed", svc.result(false to "ble hid init failed") { run("c") })
        // Raw script sent verbatim (no trimming), matching the ViewModel.
        assertEquals(listOf("ble_badble STRING hi", "ble_badble a", "ble_badble b", "ble_badble c"), runner.cmds)
        assertTrue(runner.calls.all { it.second == 650_000L })
        assertEquals(650_000L, BadBleService.TIMEOUT_MS)
    }

    @Test fun blankOrDisconnectedRejectedWithoutSending() = runBlocking {
        val svc = svc()
        svc.rejects("Script is empty.") { run("") }
        svc.rejects("Script is empty.") { run("   \n  ") }
        connected = false
        svc.rejects(notConnected) { run("x") }
    }
    @Test fun stopSupersedesWaitingRun() = runBlocking {
        val svc = svc()
        runner.sticky = true
        svc.run("payload")
        until { runner.calls.size == 1 }
        svc.stop()
        runner.reply(0, true, "late")
        delay(20)
        assertEquals("Stopped." to false, svc.state())
    }
}
