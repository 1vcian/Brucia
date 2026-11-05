package app.brucia

import app.brucia.wifi.WifiMacService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WifiMacServiceTest {
    private val runner = GateRunner()

    private suspend fun WifiMacService.result(reply: Pair<Boolean, String>, call: WifiMacService.() -> Unit): String {
        runner.auto = reply
        call()
        until { !busy.value }
        return status.value
    }

    @Test fun getAndSetTextsAndCommands() = runVirtual {
        val svc = WifiMacService(this, runner, { true })
        assertEquals("AA:BB:CC:DD:EE:FF", svc.result(true to " AA:BB:CC:DD:EE:FF\n") {
            get(); assertEquals("" to true, status.value to busy.value) // no running text
        })
        assertEquals("error: wifi off", svc.result(false to "wifi off") { get() })
        assertEquals("error: failed", svc.result(false to "") { get() })
        assertEquals("02:11:22:33:44:55", svc.result(true to " 02:11:22:33:44:55\n") {
            set(" 02:11:22:33:44:55 "); assertEquals("Setting...", status.value)
        })
        assertEquals("MAC set.", svc.result(true to "") { set("   ") })
        assertEquals("error: ESP_ERR_WIFI_MAC", svc.result(false to "ESP_ERR_WIFI_MAC") { set("RANDOM") })
        assertEquals(listOf("wifi_get_mac", "wifi_get_mac", "wifi_get_mac", "wifi_set_mac 02:11:22:33:44:55",
            "wifi_set_mac random", "wifi_set_mac RANDOM"), runner.cmds)
    }

    @Test fun macFormatMirrorsFirmware() {
        listOf("random", "Random", "aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF", "0a:1B:2c:3D:4e:5F")
            .forEach { assertTrue(it, WifiMacService.isValidMacArg(it)) }
        listOf("", "rand", "aa-bb-cc-dd-ee-ff", "aabb:cc:dd:ee:ff:", "aa:bb:cc:dd:ee:f", "aa:bb:cc:dd:ee:ff0",
            "aa:bb:cc:dd:ee:gg").forEach { assertFalse(it, WifiMacService.isValidMacArg(it)) }
    }

    @Test fun invalidMacOrNoConnectionIsNotSent() = runVirtual {
        var connected = false
        val svc = WifiMacService(this, runner, { connected })
        svc.get()
        assertEquals("Connect the device over USB first.", svc.status.value)
        connected = true
        for (bad in listOf("aa-bb-cc-dd-ee-ff", "aa:bb:cc:dd:ee:f", "aa:bb:cc:dd:ee:ff0")) {
            svc.set(bad)
            assertEquals("error: usage: wifi_set_mac <aa:bb:cc:dd:ee:ff|random>" to false, svc.status.value to svc.busy.value)
        }
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }
}
