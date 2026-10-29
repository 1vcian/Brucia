package app.brucia

import app.brucia.wardrive.DeviceCaps
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsTest {

    @Test fun absentCapsTreatedAsAllPresent() {
        val c = DeviceCaps.fromJson(null)
        assertTrue(c.cc1101 && c.nfc && c.nrf24 && c.fm && c.ir && c.mic && c.speaker && c.wifi && c.ble && c.lora && c.eth)
    }

    @Test fun parsesExplicitFlags() {
        val json = JSONObject()
            .put("cc1101", false).put("nfc", true).put("nrf24", false).put("fm", false)
            .put("ir", true).put("mic", false).put("speaker", true).put("wifi", true).put("ble", false)
        val c = DeviceCaps.fromJson(json)
        assertFalse(c.cc1101)
        assertTrue(c.nfc)
        assertFalse(c.nrf24)
        assertFalse(c.fm)
        assertTrue(c.ir)
        assertFalse(c.mic)
        assertTrue(c.speaker)
        assertTrue(c.wifi)
        assertFalse(c.ble)
    }

    @Test fun missingKeyInPresentObjectDefaultsTrue() {
        // caps object present but only nrf24 reported -> the rest stay available.
        val c = DeviceCaps.fromJson(JSONObject().put("nrf24", false))
        assertFalse(c.nrf24)
        assertTrue(c.wifi)
        assertTrue(c.nfc)
        assertTrue(c.lora)
        assertTrue(c.eth)
    }

    @Test fun parsesLoraAndEthFlags() {
        val c = DeviceCaps.fromJson(JSONObject().put("lora", false).put("eth", true))
        assertFalse(c.lora)
        assertTrue(c.eth)
    }
}
