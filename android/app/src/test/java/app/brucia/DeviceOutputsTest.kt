package app.brucia

import app.brucia.device.DeviceOutputs
import app.brucia.device.DeviceOutputs.Output.LED
import app.brucia.device.DeviceOutputs.Output.SCREEN
import org.junit.Assert.assertEquals
import org.junit.Test

/** Device screen/LED switches: held outside the UI, so a recreated Device screen shows them as set. */
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
}
