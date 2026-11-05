package app.brucia

import app.brucia.device.DeviceOutputs
import app.brucia.device.DeviceOutputs.Output.LED
import app.brucia.device.DeviceOutputs.Output.SCREEN
import app.brucia.wardrive.DeviceInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Device screen/LED switches: held outside the UI, so a recreated Device screen shows them as set, and
 * set from CAPS scrOn/ledOn when the firmware reports them (older firmware: what the app asked for).
 */
class DeviceOutputsTest {

    @Test fun requestIsShownAndSurvivesAnotherReader() {
        val outputs = DeviceOutputs()
        assertEquals(DeviceOutputs.State(screenOn = true, ledOn = true), outputs.state.value)
        val t = outputs.request(SCREEN, false)
        outputs.finish(SCREEN, t, false, ok = true)
        outputs.request(LED, false)
        // A recreated screen reads the same holder: both still off.
        assertEquals(DeviceOutputs.State(screenOn = false, ledOn = false), outputs.state.value)
        assertEquals("scr 0", DeviceOutputs.command(SCREEN, false))
        assertEquals("led 1", DeviceOutputs.command(LED, true))
    }

    @Test fun failedRequestIsUndoneUnlessANewerOneReplacedIt() {
        val outputs = DeviceOutputs()
        val off = outputs.request(SCREEN, false)
        outputs.finish(SCREEN, off, false, ok = false)
        assertEquals(true, outputs.state.value.screenOn)

        val first = outputs.request(LED, false)
        val second = outputs.request(LED, true)
        val third = outputs.request(LED, false)
        outputs.finish(LED, first, false, ok = false)
        outputs.finish(LED, second, true, ok = false)
        assertEquals(false, outputs.state.value.ledOn)
        outputs.finish(LED, third, false, ok = false)
        assertEquals(true, outputs.state.value.ledOn)
    }

    @Test fun disconnectRestoresWhatWasOffAndIgnoresLateReplies() {
        val outputs = DeviceOutputs()
        assertEquals(emptyList<String>(), outputs.disconnect())
        val t = outputs.request(SCREEN, false)
        outputs.request(LED, false)
        outputs.request(LED, true)
        assertEquals(listOf("scr 1"), outputs.disconnect())
        assertEquals(DeviceOutputs.State(), outputs.state.value)
        outputs.finish(SCREEN, t, false, ok = false)
        assertEquals(DeviceOutputs.State(), outputs.state.value)
    }

    private fun caps(json: String) = DeviceInfo.fromCaps(JSONObject(json))

    @Test fun newFirmwareCapsSetTheSwitchesOnReconnect() {
        val outputs = DeviceOutputs()
        // App restarted or reconnected within the device's 15 s hold: the device still has both off.
        val info = caps("""{"board":"b","bright":80,"scrOn":false,"ledOn":false,"scrBright":0}""")
        assertEquals(false, info.screenOn)
        assertEquals(false, info.ledOn)
        assertEquals(0, info.screenLevel)
        outputs.reported(info.screenOn, info.ledOn)
        assertEquals(DeviceOutputs.State(screenOn = false, ledOn = false), outputs.state.value)
        // Slider shows the level the screen comes back at, not the 0 of the hold.
        assertEquals(80, info.screenBrightShown)

        val on = caps("""{"board":"b","bright":80,"scrOn":true,"ledOn":true,"scrBright":35}""")
        outputs.reported(on.screenOn, on.ledOn)
        assertEquals(DeviceOutputs.State(), outputs.state.value)
        assertEquals(35, on.screenBrightShown) // live level after scr_bright, not the saved 80
    }

    @Test fun oldFirmwareCapsKeepCurrentBehaviour() {
        val outputs = DeviceOutputs()
        val old = caps("""{"board":"b","bright":60,"led":255,"ledBright":40}""")
        assertNull(old.screenOn)
        assertNull(old.ledOn)
        assertNull(old.screenLevel)
        assertEquals(60, old.screenBrightShown)
        outputs.reported(old.screenOn, old.ledOn)
        assertEquals(DeviceOutputs.State(), outputs.state.value)
        // The app's own request still shows, and an old CAPS does not undo it.
        outputs.finish(SCREEN, outputs.request(SCREEN, false), false, ok = true)
        outputs.reported(old.screenOn, old.ledOn)
        assertEquals(false, outputs.state.value.screenOn)
        // Malformed values are not taken as a report.
        val odd = caps("""{"board":"b","scrOn":"false","ledOn":0,"scrBright":101}""")
        assertNull(odd.screenOn)
        assertNull(odd.ledOn)
        assertNull(odd.screenLevel)
        assertEquals(-1, caps("""{"board":"b"}""").screenBrightShown)
    }

    @Test fun reportDoesNotOverrideARequestStillOnTheWire() {
        val outputs = DeviceOutputs()
        val t = outputs.request(LED, false)
        // CAPS answered before the device read `led 0`: it still says on.
        outputs.reported(screenOn = false, ledOn = true)
        assertEquals(DeviceOutputs.State(screenOn = false, ledOn = false), outputs.state.value)
        outputs.finish(LED, t, false, ok = true)
        outputs.reported(screenOn = false, ledOn = true)
        assertEquals(true, outputs.state.value.ledOn)
        // After a disconnect nothing is pending: the next session's report applies.
        outputs.request(SCREEN, true)
        outputs.disconnect()
        outputs.reported(screenOn = false, ledOn = false)
        assertEquals(DeviceOutputs.State(screenOn = false, ledOn = false), outputs.state.value)
    }
}
