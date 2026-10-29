package app.brucia.wardrive

import org.json.JSONObject

/**
 * Hardware capabilities reported in the CAPS handshake under d.caps. Each flag says whether
 * the corresponding module/radio is present. When d.caps is absent (older firmware) everything
 * defaults to true so no tool is hidden; a present object with a missing key also defaults true.
 */
data class DeviceCaps(
    val cc1101: Boolean = true,
    val nfc: Boolean = true,
    val nrf24: Boolean = true,
    val fm: Boolean = true,
    val ir: Boolean = true,
    val mic: Boolean = true,
    val speaker: Boolean = true,
    val wifi: Boolean = true,
    val ble: Boolean = true,
    val lora: Boolean = true,
    val eth: Boolean = true,
) {
    companion object {
        /** Parse the d.caps object; null (older firmware) -> all capabilities present. */
        fun fromJson(obj: JSONObject?): DeviceCaps {
            if (obj == null) return DeviceCaps()
            return DeviceCaps(
                cc1101 = obj.optBoolean("cc1101", true),
                nfc = obj.optBoolean("nfc", true),
                nrf24 = obj.optBoolean("nrf24", true),
                fm = obj.optBoolean("fm", true),
                ir = obj.optBoolean("ir", true),
                mic = obj.optBoolean("mic", true),
                speaker = obj.optBoolean("speaker", true),
                wifi = obj.optBoolean("wifi", true),
                ble = obj.optBoolean("ble", true),
                lora = obj.optBoolean("lora", true),
                eth = obj.optBoolean("eth", true),
            )
        }
    }
}

/** Parsed device capabilities from the CAPS handshake. */
data class DeviceInfo(
    val board: String,
    val chip: String,
    val fw: String,
    val modules: List<String>,
    val battery: Int = -1, // percent, -1 = unknown
    val caps: DeviceCaps = DeviceCaps(),
    val themeRgb565: Int = 0, // device UI primary color (RGB565); 0 = unknown
    val ledRgb: Int = 0,      // device LED color (0xRRGGBB)
    val ledBright: Int = -1,  // device LED brightness 0..100; -1 = unknown
    val ledEffect: Int = 0,   // device LED animation 0..9
    val screenBright: Int = -1, // device screen brightness 0..100; -1 = unknown
)
