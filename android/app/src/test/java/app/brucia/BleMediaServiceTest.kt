package app.brucia

import app.brucia.ble.BleMediaService
import app.brucia.session.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BleMediaServiceTest {
    private val runner = GateRunner()
    @Volatile private var connected = true
    private val notConnected = "Connect the device over USB first."
    private val badMac = "Enter a MAC like AA:BB:CC:DD:EE:FF."

    private fun CoroutineScope.svc(r: CommandRunner = runner) = BleMediaService(this, r, { connected })
    private fun BleMediaService.state() = status.value to busy.value

    private suspend fun BleMediaService.result(reply: Pair<Boolean, String>, call: BleMediaService.() -> Unit): String {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 && !busy.value }
        return status.value
    }

    private suspend fun BleMediaService.rejects(text: String, call: BleMediaService.() -> Unit) {
        val n = runner.calls.size
        call()
        assertEquals(text to false, state())
        delay(20)
        assertEquals(n, runner.calls.size)
    }

    @Test fun sendTextsTrimAndTimeout() = runBlocking {
        val svc = svc()
        svc.send("  AA:BB:CC:DD:EE:FF  ")
        assertEquals("Connecting to AA:BB:CC:DD:EE:FF..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Sent.", svc.status.value)
        assertEquals("media hijack sent", svc.result(true to "media hijack sent") { send("aa:bb:cc:dd:ee:ff") })
        assertEquals("Failed.", svc.result(false to "") { send("11:22:33:44:55:66") })
        assertEquals("no connect", svc.result(false to "no connect") { send("11:22:33:44:55:66") })
        assertEquals(listOf("ble_media AA:BB:CC:DD:EE:FF", "ble_media aa:bb:cc:dd:ee:ff",
            "ble_media 11:22:33:44:55:66", "ble_media 11:22:33:44:55:66"), runner.cmds)
        assertTrue(runner.calls.all { it.second == 650_000L })
        assertEquals(650_000L, BleMediaService.TIMEOUT_MS)
    }

    @Test fun malformedOrDisconnectedRejectedWithoutSending() = runBlocking {
        val svc = svc()
        for (m in listOf("", "AA:BB:CC:DD:EE", "GG:BB:CC:DD:EE:FF", "AABBCCDDEEFF", "AA:BB:CC:DD:EE:FF:00")) {
            svc.rejects(badMac) { send(m) }
        }
        connected = false
        svc.rejects(notConnected) { send("AA:BB:CC:DD:EE:FF") }
    }
    @Test fun stopSupersedesWaitingSend() = runBlocking {
        val svc = svc()
        runner.sticky = true
        svc.send("AA:BB:CC:DD:EE:FF")
        until { runner.calls.size == 1 }
        svc.stop()
        runner.reply(0, true, "late")
        delay(20)
        assertEquals("Stopped." to false, svc.state())
    }
}
