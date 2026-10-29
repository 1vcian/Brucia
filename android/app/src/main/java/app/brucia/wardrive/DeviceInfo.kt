package app.brucia.wardrive

import org.json.JSONObject

/**
 * Hardware capabilities reported in the CAPS handshake under d.caps. Each flag says whether
 * the corresponding module/radio is present. Only an explicit true enables a module. Missing/invalid flags (including older
 * firmware without d.caps) remain unavailable until the device reports support.
 */
data class DeviceCaps(
    val cc1101: Boolean = false,
    val nfc: Boolean = false,
    val nrf24: Boolean = false,
    val fm: Boolean = false,
    val ir: Boolean = false,
    val mic: Boolean = false,
    val speaker: Boolean = false,
    val wifi: Boolean = false,
    val ble: Boolean = false,
    val lora: Boolean = false,
    val eth: Boolean = false,
    val lfRfid: Boolean = false,
    val ibutton: Boolean = false,
) {
    /** Unknown capability names must not bypass hardware gating. */
    fun allows(capability: String?): Boolean = when (capability) {
        null -> true
        "cc1101" -> cc1101
        "nfc" -> nfc
        "nrf24" -> nrf24
        "fm" -> fm
        "ir" -> ir
        "mic" -> mic
        "speaker" -> speaker
        "wifi" -> wifi
        "ble" -> ble
        "lora" -> lora
        "eth" -> eth
        "lf_rfid" -> lfRfid
        "ibutton" -> ibutton
        else -> false
    }

    fun allowsStream(stream: String): Boolean = when (stream) {
        "wifi.ap", "wifi.sta", "wifi.probe", "wifi.monitor" -> wifi
        "ble.scan" -> ble
        "subghz.raw", "subghz.spectrum" -> cc1101
        "mic.pcm", "mic.spectrum" -> mic
        else -> false
    }

    companion object {
        /** Parse the d.caps object; null (older firmware) -> no confirmed capabilities. */
        fun fromJson(obj: JSONObject?): DeviceCaps {
            if (obj == null) return DeviceCaps()
            return DeviceCaps(
                cc1101 = obj.opt("cc1101") == true,
                nfc = obj.opt("nfc") == true,
                nrf24 = obj.opt("nrf24") == true,
                fm = obj.opt("fm") == true,
                ir = obj.opt("ir") == true,
                mic = obj.opt("mic") == true,
                speaker = obj.opt("speaker") == true,
                wifi = obj.opt("wifi") == true,
                ble = obj.opt("ble") == true,
                lora = obj.opt("lora") == true,
                eth = obj.opt("eth") == true,
                lfRfid = obj.opt("lf_rfid") == true,
                ibutton = obj.opt("ibutton") == true,
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
