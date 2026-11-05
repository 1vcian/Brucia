package app.brucia

import app.brucia.protocol.CommandCapabilities
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import app.brucia.wardrive.DeviceCaps
import app.brucia.wardrive.DeviceFeatures
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandCapabilitiesTest {
    private val families = mapOf(
        "wifi" to listOf("wifi_connect lab", "wifi_handshake 00:11:22:33:44:55 6 10", "wifi_pmkid x", "wifi_beacon_spam 1 x", "arp_spoof x", "evil_portal_start x", "responder 5", "karma 5", "brucegotchi 5", "jam_detect 1", "wifi_wids 1", "espnow_send test"),
        "ble" to listOf("ble_hid_begin", "ble_gatt_read x", "ble_badble x", "ble_media x", "ble_spam 1", "ble_ibeacon 1"),
        "cc1101" to listOf("subghz_replay 433920000", "subghz_config 433", "subghz_bruteforce x", "rf rx 433920000", "subghz tx x"),
        "ir" to listOf("ir_rx", "ir_tvbgone", "ir_tx_raw x", "ir tx NEC x"),
        "nfc" to listOf("rfid read", "rfid emulate", "nfc_ndef_push text x", "nfc_emv_read", "nfc_write_uid x", "mifare_keycheck x", "srix_read 1"),
        "lf_rfid" to listOf("rfid125_read 1"), "ibutton" to listOf("ibutton_read", "ibutton_write x"),
        "nrf24" to listOf("nrf24_scan 1", "nrf24_sniff 1", "nrf24_mousejack 1"),
        "lora" to listOf("lora_config", "lora_send test", "lora_recv 1"),
        "eth" to listOf("eth_status", "eth_config dhcp", "eth_mac_flood 1"),
        "fm" to listOf("fm_tx 90000", "fm_audio_begin 90000 phone", "fm_audio_data AAAA"),
        "mic" to listOf("mic_record 1"),
        "speaker" to listOf("speaker_tone 440 10", "speaker_play /test.wav", "speaker_dtmf 1", "spkr_pcm_begin", "spkr_pcm_data"),
    )

    @Test fun everyHardwareFamilyRequiresItsOwnExplicitCapability() {
        for ((cap, commands) in families) for (cmd in commands) {
            assertEquals(cmd, setOf(cap), CommandCapabilities.required(cmd))
            for (reported in families.keys) {
                val caps = DeviceCaps.fromJson(JSONObject().put(reported, true))
                val rejected = runCatching { CommandCapabilities.check(cmd, caps) }.isFailure
                assertEquals("$cmd with $reported", reported != cap, rejected)
            }
            assertTrue(runCatching { CommandCapabilities.check(cmd, null) }.isFailure)
        }
    }

    @Test fun deviceMicFmNeedsBothModulesButPhoneAudioDoesNot() {
        assertEquals(setOf("fm", "mic"), CommandCapabilities.required("fm_audio_begin 90000 mic"))
        assertTrue(runCatching { CommandCapabilities.check("fm_audio_begin 90000 mic", DeviceCaps(fm = true)) }.isFailure)
        CommandCapabilities.check("fm_audio_begin 90000 mic", DeviceCaps(fm = true, mic = true))
        CommandCapabilities.check("fm_audio_begin 90000 phone", DeviceCaps(fm = true))
    }

    @Test fun cleanupAndNonRadioCommandsRemainUsableWithoutCaps() {
        for (cmd in listOf("fm_audio_stop", "speaker_stop", "spkr_pcm_stop", "ble_hid_end",
            "evil_portal_stop", "wifi_disconnect", "responder_stop", "karma_stop", "brucegotchi_stop",
            "clock_get", "storage list /", "fs_read /a", "theme_set ffff", "companion_exit", "script_run print('x')")) {
            CommandCapabilities.check(cmd, null)
        }
    }

    @Test fun liteFirmwareRejectsOnlyItsCompiledOutCommandsWithTheReason() {
        val caps = DeviceCaps(nfc = true, ir = true, ble = true, wifi = true)
        val lite = DeviceFeatures.fromJson(JSONObject().put("mifare_keycheck", false)
            .put("ir_cmds", false).put("ble_gatt", false).put("diag", false))
        val omitted = mapOf(
            "mifare_keycheck A0A1A2A3A4A5,FFFFFFFFFFFF" to DeviceFeatures.MIFARE_KEYCHECK,
            "ir_rx" to DeviceFeatures.IR_CMDS, "ir_tvbgone eu" to DeviceFeatures.IR_CMDS,
            "ir_tx_raw 38 9000,4500" to DeviceFeatures.IR_CMDS, "ir_jammer 2" to DeviceFeatures.IR_CMDS,
            "ble_gatt_connect AA:BB:CC:DD:EE:FF" to DeviceFeatures.BLE_GATT,
            "ble_gatt_read AA:BB:CC:DD:EE:FF 2a00" to DeviceFeatures.BLE_GATT,
        )
        for ((cmd, group) in omitted) {
            assertEquals(cmd, group, CommandCapabilities.firmwareGroup(cmd))
            val e = runCatching { CommandCapabilities.check(cmd, caps, lite) }.exceptionOrNull()
            assertEquals(cmd, DeviceFeatures.OMITTED_REASON, e?.message)
            // Full builds / older firmware (no flags) and a missing features object still send it.
            CommandCapabilities.check(cmd, caps, DeviceFeatures())
            CommandCapabilities.check(cmd, caps, null)
        }
        // Bruce's own CLI and the rest of each tool stay usable on LITE (the app's fallbacks).
        for (cmd in listOf("ir rx", "ir tx NEC 04000000 08000000", "ir tx_raw 38000 \"9000 4500\"",
            "rfid read", "nfc_ndef_push text hi", "srix_read 5", "ble_spam 3", "ble_ibeacon 3",
            "mifare_keycheck_stop", "mifare_nested", "wifi_connect lab")) {
            assertNull(cmd, CommandCapabilities.firmwareGroup(cmd))
            CommandCapabilities.check(cmd, caps, lite)
        }
        // Hardware gating still comes first.
        val e = runCatching { CommandCapabilities.check("ir_rx", DeviceCaps(), lite) }.exceptionOrNull()
        assertEquals("No confirmed hardware: ir", e?.message)
    }

    @Test fun whitespaceAndArgumentsCannotChangeTheHardwareFamily() {
        assertEquals(setOf("nfc"), CommandCapabilities.required(" \t rfid\t read "))
        assertEquals(setOf("speaker"), CommandCapabilities.required("speaker_play /wifi_connect.wav"))
        assertEquals(emptySet<String>(), CommandCapabilities.required("storage list /ibutton_read"))
    }

    @Test(timeout = 5000) fun sessionRejectsTextJsonAndStructuredCommandsWithoutWritingOrLeakingPending() = runVirtual {
        var writes = 0
        lateinit var s: CommandSession
        val t = object : Transport {
            override val isOpen = true
            override fun read(buf: ByteArray, timeoutMs: Int) = 0
            override fun close() = Unit
            override fun write(bytes: ByteArray) {
                writes++
                val frame = JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))
                s.route(JSONObject().put("t", Kind.ACK).put("id", frame.getInt("id"))
                    .put("d", JSONObject().put("ok", true)))
            }
        }
        var caps = DeviceCaps()
        s = CommandSession(CompanionClient(t), t, preflight = { CommandCapabilities.check(it, caps) })
        assertFalse(s.await("lora_recv 1", 50).first)
        assertFalse(s.awaitJson("eth_status", 50).first)
        assertFalse(s.awaitFields("spkr_pcm_begin", JSONObject().put("sr", 16000), 50).first)
        assertEquals(0, writes)
        assertFalse(s.hasPending)
        caps = DeviceCaps(speaker = true)
        assertTrue(s.awaitFields("spkr_pcm_begin", JSONObject().put("sr", 16000), 50).first)
        assertEquals(1, writes)
        assertFalse(s.hasPending)
    }
}
