package app.brucia

import app.brucia.device.DeviceAppearanceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DeviceAppearanceServiceTest {
    private val runner = GateRunner()
    private val themes = CopyOnWriteArrayList<Int>()
    private val leds = CopyOnWriteArrayList<Int>()

    private fun CoroutineScope.svc(connected: () -> Boolean = { true }) =
        DeviceAppearanceService(this, runner, connected, { themes += it }, { leds += it })

    private suspend fun DeviceAppearanceService.result(reply: Pair<Boolean, String>, call: DeviceAppearanceService.() -> Unit): String {
        runner.auto = reply
        call()
        until { !busy.value }
        return status.value
    }

    @Test fun commandsTextsAndCallbacks() = runVirtual {
        val svc = svc()
        assertEquals("Theme set.", svc.result(true to "") {
            setTheme(0xFF8040); assertEquals("" to true, status.value to busy.value) // no running text
        })
        assertEquals("theme set", svc.result(true to "theme set") { setTheme(0x0000FF) })
        assertEquals("Failed.", svc.result(false to "") { setTheme(0xFFFFFF) })
        assertEquals("LED set.", svc.result(true to "") { setLedColor(0x7F123456) })
        assertEquals("no rgb led", svc.result(false to "no rgb led") { setLedColor(0xABCDEF) })
        assertEquals("Saved to device.", svc.result(true to "saved") { save() })
        assertEquals("Save failed: error", svc.result(false to "") { save() })
        assertEquals("LED brightness set.", svc.result(true to "") { setLedBright(150) })
        assertEquals("led bright 0%", svc.result(true to "led bright 0%") { setLedBright(-5) })
        assertEquals("LED effect set.", svc.result(true to "") { setLedEffect(12) })
        assertEquals("Failed.", svc.result(false to "") { setLedEffect(-1) })
        assertEquals(listOf("theme_set fc08", "theme_set 001f", "theme_set ffff", "led_set 123456", "led_set abcdef",
            "config_save", "config_save", "led_bright 100", "led_bright 0", "led_effect 9", "led_effect 0"), runner.cmds)
        assertTrue(runner.calls.all { it.second == 10_000L })
        assertEquals(listOf(0xFC08, 0x001F), themes.toList())
        assertEquals(listOf(0x123456), leds.toList())
    }

    @Test fun supersededOrStoppedReplyNeverAppliesColor() = runVirtual {
        runner.sticky = true
        val svc = svc()
        svc.setTheme(0xFF0000)
        until { runner.calls.size == 1 }
        svc.setLedColor(0x00FF00)
        until { runner.calls.size == 2 }
        runner.reply(0, true, "")
        delay(20)
        assertTrue(themes.isEmpty())
        assertEquals("" to true, svc.status.value to svc.busy.value)
        runner.reply(1, true, "")
        until { !svc.busy.value }
        assertEquals("LED set." to listOf(0x00FF00), svc.status.value to leds.toList())

        svc.setTheme(0x00FF00)
        until { runner.calls.size == 3 }
        svc.stop()
        runner.reply(2, true, "")
        delay(20)
        assertEquals("Stopped." to emptyList<Int>(), svc.status.value to themes.toList())
    }

    @Test fun notConnectedIsNotSent() = runVirtual {
        val svc = svc { false }
        svc.setTheme(0x123456); svc.setLedColor(1); svc.save(); svc.setLedBright(5); svc.setLedEffect(1)
        assertEquals("Connect the device over USB first." to false, svc.status.value to svc.busy.value)
        delay(20)
        assertTrue(runner.calls.isEmpty() && themes.isEmpty() && leds.isEmpty())
    }

    @Test fun rgb565Conversion() {
        assertEquals(0xFFFF, DeviceAppearanceService.toRgb565(0xFFFFFF))
        assertEquals(0xF800, DeviceAppearanceService.toRgb565(0xFF0000))
        assertEquals(0x07E0, DeviceAppearanceService.toRgb565(0x00FF00))
        assertEquals(0x0000, DeviceAppearanceService.toRgb565(0x070307))
    }
}
