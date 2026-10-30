package app.brucia

import app.brucia.device.EspNowService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EspNowServiceTest {
    private val runner = GateRunner()

    private suspend fun EspNowService.result(reply: Pair<Boolean, String>, call: EspNowService.() -> Unit): String {
        runner.auto = reply
        call()
        until { !busy.value }
        return status.value
    }

    @Test fun textsCommandAndTimeout() = runBlocking {
        val svc = EspNowService(this, runner, { true })
        assertEquals("Broadcast sent.", svc.result(true to "") {
            send("  hello there \n"); assertEquals("Broadcasting..." to true, status.value to busy.value)
        })
        assertEquals("sent 11 bytes", svc.result(true to "sent 11 bytes") { send("hello") })
        assertEquals("ESP_ERR_ESPNOW_NOT_INIT", svc.result(false to "ESP_ERR_ESPNOW_NOT_INIT") { send("hello") })
        assertEquals("Broadcast failed.", svc.result(false to "") { send("hello") })
        assertEquals(listOf("espnow_send hello there" to 10_000L) + List(3) { "espnow_send hello" to 10_000L },
            runner.calls.toList())
    }

    @Test fun localRejectionsAreNotSent() = runBlocking {
        var connected = false
        val svc = EspNowService(this, runner, { connected })
        svc.send("hi")
        assertEquals("Connect the device over USB first.", svc.status.value)
        connected = true
        svc.send("  \n")
        assertEquals("Enter a message first." to false, svc.status.value to svc.busy.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }
}
