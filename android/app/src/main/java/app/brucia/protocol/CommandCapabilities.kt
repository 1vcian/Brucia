package app.brucia.protocol

import app.brucia.wardrive.DeviceCaps
import app.brucia.wardrive.DeviceFeatures

/** Hardware preflight for native companion commands and the app's radio CLI aliases, plus the
 * companion endpoint groups a LITE firmware reports as compiled out (CAPS `features.<group>: false`).
 * Unknown CLI/script contents remain the firmware's responsibility, not a sandbox.
 * Stop commands are always permitted on an open session so lost CAPS cannot block cleanup.
 */
object CommandCapabilities {
    private val stops = setOf("fm_stop", "fm_audio_stop", "speaker_stop", "spkr_pcm_stop",
        "ble_hid_end", "evil_portal_stop", "wifi_disconnect", "responder_stop", "karma_stop", "brucegotchi_stop")

    fun required(command: String): Set<String> {
        val words = command.trim().split(Regex("\\s+"), limit = 4)
        val verb = words.firstOrNull().orEmpty()
        if (verb in stops) return emptySet()
        val cap = when {
            verb == "rfid125_read" -> "lf_rfid"
            verb.startsWith("ibutton_") -> "ibutton"
            verb.startsWith("nrf24_") -> "nrf24"
            verb.startsWith("lora_") -> "lora"
            verb.startsWith("eth_") -> "eth"
            verb.startsWith("fm_") -> "fm"
            verb.startsWith("speaker_") || verb.startsWith("spkr_pcm_") -> "speaker"
            verb == "mic_record" -> "mic"
            verb.startsWith("subghz_") || verb in setOf("rf", "subghz") -> "cc1101"
            verb.startsWith("ir_") || verb == "ir" -> "ir"
            verb.startsWith("nfc_") || verb.startsWith("mifare_") || verb in setOf("srix_read", "rfid") -> "nfc"
            verb.startsWith("ble_") -> "ble"
            verb.startsWith("wifi_") || verb.startsWith("evil_portal_") ||
                verb in setOf("arp_spoof", "responder", "karma", "brucegotchi", "jam_detect", "wids", "espnow_send") -> "wifi"
            else -> return emptySet()
        }
        return if (verb == "fm_audio_begin" && words.getOrNull(2) == "mic") setOf(cap, "mic") else setOf(cap)
    }

    /** The companion endpoint group (DeviceFeatures.OMITTABLE) a LITE build may compile out, or null. */
    fun firmwareGroup(command: String): String? {
        val verb = command.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        return when {
            verb == "mifare_keycheck" -> DeviceFeatures.MIFARE_KEYCHECK
            verb in setOf("ir_rx", "ir_tvbgone", "ir_tx_raw", "ir_jammer") -> DeviceFeatures.IR_CMDS
            verb.startsWith("ble_gatt_") -> DeviceFeatures.BLE_GATT
            else -> null
        }
    }

    fun check(command: String, caps: DeviceCaps?, features: DeviceFeatures? = null) {
        val missing = required(command).filter { caps?.allows(it) != true }
        check(missing.isEmpty()) { "No confirmed hardware: ${missing.joinToString(", ")}" }
        val group = firmwareGroup(command)
        check(group == null || features?.omits(group) != true) { DeviceFeatures.OMITTED_REASON }
    }
}
