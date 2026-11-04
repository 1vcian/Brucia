package app.brucia

import app.brucia.wardrive.DeviceCaps
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsTest {
    @Test fun verifiedFileTransfersRequireExplicitFirmwareSupport() {
        assertFalse(app.brucia.wardrive.DeviceFeatures.fromJson(null).verifiedFiles)
        assertFalse(app.brucia.wardrive.DeviceFeatures.fromJson(JSONObject().put("verified_files", "true")).verifiedFiles)
        assertTrue(app.brucia.wardrive.DeviceFeatures.fromJson(JSONObject().put("verified_files", true)).verifiedFiles)
    }

    @Test fun transactionalUploadSupportRequiresItsOwnLiteralFlag() {
        val parse = app.brucia.wardrive.DeviceFeatures.Companion::fromJson
        assertFalse(parse(null).transactionalUploads)
        assertFalse(parse(JSONObject().put("verified_files", true)).transactionalUploads)
        assertFalse(parse(JSONObject().put("transactional_uploads", "true")).transactionalUploads)
        assertTrue(parse(JSONObject().put("transactional_uploads", true)).transactionalUploads)
    }

    @Test fun asyncSpeakerPlayRequiresItsOwnLiteralFlag() {
        val parse = app.brucia.wardrive.DeviceFeatures.Companion::fromJson
        assertFalse(parse(null).speakerPlayAsync)
        assertFalse(parse(JSONObject().put("verified_files", true).put("transactional_uploads", true)).speakerPlayAsync)
        assertFalse(parse(JSONObject().put("speaker_play_async", "true")).speakerPlayAsync)
        assertFalse(parse(JSONObject().put("speaker_play_async", 1)).speakerPlayAsync)
        assertTrue(parse(JSONObject().put("speaker_play_async", true)).speakerPlayAsync)
    }

    @Test fun inFlightMifareStopRequiresItsOwnLiteralFlag() {
        val parse = app.brucia.wardrive.DeviceFeatures.Companion::fromJson
        assertFalse(parse(null).mifareKeycheckStop)
        assertFalse(parse(JSONObject().put("mifare_keycheck_report", true)).mifareKeycheckStop)
        assertFalse(parse(JSONObject().put("mifare_keycheck_stop", "true")).mifareKeycheckStop)
        assertTrue(parse(JSONObject().put("mifare_keycheck_stop", true)).mifareKeycheckStop)
    }

    @Test fun absentCapsDoNotInventHardware() {
        val c = DeviceCaps.fromJson(null)
        assertFalse(c.cc1101 || c.nfc || c.nrf24 || c.fm || c.ir || c.mic || c.speaker || c.wifi || c.ble || c.lora || c.eth)
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

    @Test fun missingKeyInPresentObjectStaysUnavailable() {
        // Reporting one module must not enable unrelated, unreported modules.
        val c = DeviceCaps.fromJson(JSONObject().put("nrf24", false))
        assertFalse(c.nrf24)
        assertFalse(c.wifi)
        assertFalse(c.nfc)
        assertFalse(c.lora)
        assertFalse(c.eth)
    }

    @Test fun malformedFlagsDoNotEnableModules() {
        val c = DeviceCaps.fromJson(JSONObject()
            .put("cc1101", "true").put("nfc", 1).put("lora", JSONObject.NULL)
            .put("eth", JSONObject()).put("speaker", true))
        assertFalse(c.cc1101)
        assertFalse(c.nfc)
        assertFalse(c.lora)
        assertFalse(c.eth)
        assertTrue(c.speaker)
    }

    @Test fun gatesCompositeScreenStreamsByConfirmedHardware() {
        val c = DeviceCaps.fromJson(JSONObject().put("wifi", true).put("ble", true))
        assertTrue(c.allowsStream("wifi.ap"))
        assertTrue(c.allowsStream("wifi.monitor"))
        assertTrue(c.allowsStream("ble.scan"))
        assertFalse(c.allowsStream("subghz.spectrum"))
        assertFalse(c.allowsStream("subghz.raw"))
        assertFalse(c.allowsStream("mic.pcm"))
        assertFalse(c.allowsStream("wifi.unknown"))
        assertTrue(c.allows(null))
        assertFalse(c.allows("unknown-module"))
        assertFalse(DeviceCaps().allowsStream("wifi.ap"))
    }

    @Test fun nfcDoesNotImplyAnLfReaderOrOneWireInterface() {
        val c = DeviceCaps.fromJson(JSONObject().put("nfc", true))
        assertTrue(c.nfc)
        assertFalse(c.lfRfid)
        assertFalse(c.ibutton)
        assertFalse(c.allows("lf_rfid"))
        assertFalse(c.allows("ibutton"))
    }

    @Test fun parsesLoraAndEthFlags() {
        val c = DeviceCaps.fromJson(JSONObject().put("lora", false).put("eth", true))
        assertFalse(c.lora)
        assertTrue(c.eth)
    }
}
