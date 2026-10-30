package app.brucia

import app.brucia.wifi.WifiJoinService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WifiJoinServiceTest {
    private val runner = GateRunner()

    private suspend fun WifiJoinService.result(reply: Pair<Boolean, String>, call: WifiJoinService.() -> Unit): String {
        runner.auto = reply
        call()
        until { !busy.value }
        return status.value
    }

    @Test fun textsCommandsAndTimeouts() = runBlocking {
        val svc = WifiJoinService(this, runner, { true })
        assertEquals("connected Home 192.168.1.5", svc.result(true to " connected Home 192.168.1.5\n") {
            refresh(); assertEquals("" to true, status.value to busy.value) // no running text
        })
        assertEquals("error: failed", svc.result(false to "") { refresh() })
        assertEquals("Connected - IP 192.168.1.5", svc.result(true to " 192.168.1.5\n") {
            join("  My Net ", "p a ss"); assertEquals("Connecting (up to ~10s)...", status.value)
        })
        assertEquals("Failed: connect failed", svc.result(false to "connect failed") { join("x", "") })
        assertEquals("Failed: timeout", svc.result(false to "") { join("x", "") })
        assertEquals("Disconnected.", svc.result(true to "disconnected") { leave() })
        assertEquals("error: closed", svc.result(false to "closed") { leave() })
        assertEquals(listOf("wifi_status" to 10_000L, "wifi_status" to 10_000L, "wifi_connect My Net\u001fp a ss" to 30_000L,
            "wifi_connect x\u001f" to 30_000L, "wifi_connect x\u001f" to 30_000L, "wifi_disconnect" to 10_000L,
            "wifi_disconnect" to 10_000L), runner.calls.toList())
    }

    @Test fun localRejectionsAreNotSent() = runBlocking {
        var connected = false
        val svc = WifiJoinService(this, runner, { connected })
        svc.join("Home", "pw")
        assertEquals("Connect the device over USB first.", svc.status.value)
        connected = true
        svc.join(" \t", "pw")
        assertEquals("Enter an SSID first." to false, svc.status.value to svc.busy.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test fun refreshSupersedesPendingJoin() = runBlocking {
        val svc = WifiJoinService(this, runner, { true })
        svc.join("Home", "pw")
        until { runner.calls.size == 1 }
        svc.refresh()
        until { runner.calls.size == 2 }
        runner.reply(0, true, "10.0.0.2")
        runner.reply(1, true, "disconnected")
        until { !svc.busy.value }
        assertEquals("disconnected", svc.status.value)
    }
}
