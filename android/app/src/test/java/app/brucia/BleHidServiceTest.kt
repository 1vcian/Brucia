package app.brucia

import app.brucia.ble.BleHidService
import app.brucia.session.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BleHidServiceTest {
    private val runner = GateRunner()
    @Volatile private var connected = true
    private val notConnected = "Connect the device over USB first."

    private fun CoroutineScope.svc(r: CommandRunner = runner) = BleHidService(this, r, { connected })
    private fun BleHidService.state() = status.value to busy.value

    /** begin/end go through the operation (busy), so wait for the call and the cleared busy. */
    private suspend fun BleHidService.result(reply: Pair<Boolean, String>, call: BleHidService.() -> Unit): String {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 && !busy.value }
        return status.value
    }

    /** Alongside send (no busy): auto-reply, wait for the call, let onReply run. */
    private suspend fun BleHidService.along(reply: Pair<Boolean, String>, call: BleHidService.() -> Unit) {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 }
        delay(20)
    }

    @Test fun beginTextsTimeoutAndInitialOff() = runBlocking {
        val svc = svc()
        assertEquals("off" to false, svc.state())
        svc.begin()
        assertEquals("Starting..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("advertising", svc.status.value)
        assertEquals("connected", svc.result(true to "connected") { begin() })
        assertEquals("failed", svc.result(false to "") { begin() })
        assertEquals("boom", svc.result(false to "boom") { begin() })
        assertTrue(runner.cmds.all { it == "ble_hid_begin" })
        assertTrue(runner.calls.all { it.second == 10_000L })
        assertEquals(10_000L, BleHidService.TIMEOUT_MS)
    }

    @Test fun disconnectedBeginSetsConnectTextOthersNoop() = runBlocking {
        connected = false
        val svc = svc()
        svc.begin()
        assertEquals(notConnected to false, svc.state())
        svc.end(); svc.refresh(); svc.key("next"); svc.type("hi")
        delay(20)
        assertEquals(notConnected, svc.status.value) // end/refresh/key/type changed nothing
        assertTrue(runner.calls.isEmpty())           // nothing sent at all
    }

    @Test fun refreshKeyTypeAlongsideTextsAndEmptyIgnored() = runBlocking {
        val svc = svc()
        assertEquals("advertising", svc.result(true to "") { begin() })
        svc.type("")                                       // empty -> ignored, no send
        svc.along(true to "connected") { refresh() }       // ok -> status = out
        assertEquals("connected", svc.status.value)
        svc.along(false to "err") { refresh() }            // fail -> keeps
        assertEquals("connected", svc.status.value)
        svc.along(false to "unknown key") { key("boom") }  // fail -> status = out
        assertEquals("unknown key", svc.status.value)
        svc.along(true to "key") { key("next") }           // ok -> keeps
        assertEquals("unknown key", svc.status.value)
        svc.along(true to "typed") { type("hello") }       // success -> keeps
        assertEquals("unknown key", svc.status.value)
        svc.along(false to "not connected") { type("x") }  // fail -> status = out
        assertEquals("not connected", svc.status.value)
        assertEquals(listOf("ble_hid_begin", "ble_hid_status", "ble_hid_status",
            "ble_hid_key boom", "ble_hid_key next", "ble_hid_type hello", "ble_hid_type x"), runner.cmds)
        assertTrue(runner.calls.all { it.second == 10_000L })
    }

    @Test fun endSupersedesPendingBeginToOff() = runBlocking {
        val svc = svc()
        runner.sticky = true
        svc.begin()
        until { runner.calls.size == 1 }
        assertEquals("Starting..." to true, svc.state())
        svc.end()
        until { runner.calls.size == 2 }
        runner.reply(1, true, "stopped")
        until { svc.status.value == "off" }
        assertFalse(svc.busy.value)
        runner.reply(0, true, "advertising") // late begin reply must not revive the status
        delay(20)
        assertEquals("off", svc.status.value)
        assertEquals(listOf("ble_hid_begin", "ble_hid_end"), runner.cmds)
    }

    @Test fun alongsideDoesNotSupersedePendingBegin() = runBlocking {
        val svc = svc()
        runner.sticky = true
        svc.begin()
        until { runner.calls.size == 1 }
        svc.key("next")
        until { runner.calls.size == 2 }
        runner.reply(1, true, "key")
        delay(20)
        assertEquals("Starting..." to true, svc.state()) // begin wait survived
        runner.reply(0, true, "advertising")
        until { !svc.busy.value }
        assertEquals("advertising", svc.status.value)
        assertEquals(listOf("ble_hid_begin", "ble_hid_key next"), runner.cmds)
    }
    @Test fun stopSupersedesWaitingBegin() = runBlocking {
        val svc = svc()
        runner.sticky = true
        svc.begin()
        until { runner.calls.size == 1 }
        svc.stop()
        runner.reply(0, true, "advertising")
        delay(20)
        assertFalse(svc.busy.value)
        assertEquals("Stopped.", svc.status.value) // terminal state; late reply ignored
    }
}
