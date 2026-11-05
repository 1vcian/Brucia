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

    /**
     * Clear flags whose module the firmware probed and found absent (CAPS d.evidence). Only a
     * proved absence changes gating; every other evidence value leaves the flags as reported.
     */
    fun withoutProvedAbsent(evidence: CapabilityEvidence?): DeviceCaps {
        if (evidence == null) return this
        fun keep(flag: Boolean, module: String) = flag && !evidence.provedAbsent(module)
        return copy(
            cc1101 = keep(cc1101, "cc1101"), nfc = keep(nfc, "nfc"), nrf24 = keep(nrf24, "nrf24"),
            fm = keep(fm, "fm"), ir = keep(ir, "ir"), mic = keep(mic, "mic"), speaker = keep(speaker, "speaker"),
            wifi = keep(wifi, "wifi"), ble = keep(ble, "ble"), lora = keep(lora, "lora"), eth = keep(eth, "eth"),
            lfRfid = keep(lfRfid, "lf_rfid"), ibutton = keep(ibutton, "ibutton"),
        )
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

/**
 * Parsed device capabilities from the CAPS handshake. [speakerPlayAsync]: speaker_play ACKs at
 * start and reports its end with one EVENT ev="speaker.play" (literal `features.speaker_play_async`).
 * [mifareKeycheckStop]: firmware stops an in-flight mifare_keycheck batch on a polled stop frame
 * (literal `features.mifare_keycheck_stop`). [diag]: firmware answers the `diag` recovery
 * diagnostics command (literal `features.diag`). [omitted]: companion endpoint groups a LITE
 * build compiled out, reported as a literal `false` (see [OMITTABLE]); an absent key means the
 * group is present (full builds and firmware older than the flag), so gating never regresses them.
 */
data class DeviceFeatures(
    val verifiedFiles: Boolean = false,
    val transactionalUploads: Boolean = false,
    val speakerPlayAsync: Boolean = false,
    val mifareKeycheckStop: Boolean = false,
    val diag: Boolean = false,
    // wifi_handshake/wifi_pmkid terminal ACK carries a d.desc descriptor for the file written this run
    // (literal `features.capture_descriptor`); the capture workflow imports exactly that path and
    // verifies size/CRC after transfer instead of diffing directory listings.
    val captureDescriptor: Boolean = false,
    val omitted: Set<String> = emptySet(),
) {
    /** True only when the firmware reported [group] as compiled out (literal `false`). */
    fun omits(group: String): Boolean = group in omitted

    companion object {
        const val MIFARE_KEYCHECK = "mifare_keycheck"
        const val IR_CMDS = "ir_cmds"
        const val BLE_GATT = "ble_gatt"
        /** Endpoint groups a LITE firmware build may compile out (CAPS `features.<group>: false`). */
        val OMITTABLE = listOf(MIFARE_KEYCHECK, IR_CMDS, BLE_GATT)
        const val OMITTED_REASON = "Not available on this firmware (compiled out of LITE builds)."

        fun fromJson(obj: JSONObject?) = DeviceFeatures(obj?.opt("verified_files") == true,
            obj?.opt("transactional_uploads") == true, obj?.opt("speaker_play_async") == true,
            obj?.opt("mifare_keycheck_stop") == true, obj?.opt("diag") == true,
            obj?.opt("capture_descriptor") == true,
            OMITTABLE.filter { obj?.opt(it) == false }.toSet())
    }
}

/**
 * Release provenance from the optional CAPS `d.build` object (firmware built with
 * pre_build_provenance.py). Older firmware omits it; [DeviceInfo.build] is then null. Values are
 * device-supplied text: only bounded printable strings are kept, and [dirty] is a literal JSON
 * boolean or null (unknown, e.g. a build from a source tarball without Git metadata).
 */
data class DeviceBuild(
    val commit: String? = null,
    val dirty: Boolean? = null,
    val id: String? = null,
    val time: String? = null,
    val timeSource: String? = null,
    val env: String? = null,
) {
    companion object {
        private const val MAX_LEN = 64
        private val SAFE = Regex("[A-Za-z0-9._+:\\-]{1,$MAX_LEN}")

        private fun text(obj: JSONObject, key: String): String? =
            (obj.opt(key) as? String)?.trim()?.takeIf { SAFE.matches(it) }

        /** null when the object is absent (older firmware) or carries no usable field. */
        fun fromJson(obj: JSONObject?): DeviceBuild? {
            if (obj == null) return null
            val b = DeviceBuild(
                commit = text(obj, "commit"),
                dirty = obj.opt("dirty") as? Boolean,
                id = text(obj, "id"),
                time = text(obj, "time"),
                timeSource = text(obj, "time_src"),
                env = text(obj, "env"),
            )
            return if (b == DeviceBuild()) null else b
        }
    }
}

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
    val features: DeviceFeatures = DeviceFeatures(),
    val proto: Int? = null,          // CAPS d.proto; null when absent/malformed
    val build: DeviceBuild? = null,  // CAPS d.build; null on firmware without provenance
    val evidence: CapabilityEvidence? = null, // CAPS d.evidence; null on older firmware
    /** CAPS d.rx_max: largest frame payload the device's RX buffer holds; null on older firmware. */
    val rxMax: Int? = null,
    val rxBuf: Int? = null,  // CAPS d.rx_buf: RX buffer bytes the device allocated
) {
    /** Provenance lines for the log export and session reports. */
    fun provenanceLines(): List<String> = listOf(
        "Firmware: " + fw.ifBlank { "unknown" } + " / protocol " + (proto?.toString() ?: "unknown"),
        "Board: " + board + (if (chip.isNotBlank()) " ($chip)" else ""),
    ) + buildLines()

    /** Commit/build lines (also shown on the device card); one fallback line for older firmware. */
    fun buildLines(): List<String> {
        val b = build ?: return listOf("Build: not reported by this firmware")
        return listOf(
            "Commit: " + (b.commit ?: "unknown") + when (b.dirty) { true -> " (dirty)"; false -> " (clean)"; null -> "" },
            "Build: " + (b.id ?: "unknown") +
                (b.time?.let { " / $it" + (b.timeSource?.let { s -> " ($s)" } ?: "") } ?: "") +
                (b.env?.let { " / env $it" } ?: ""),
        )
    }

    companion object {
        /** Parse a CAPS body (`d`). Unknown/extra keys are ignored; new optional fields default safely. */
        fun fromCaps(d: JSONObject): DeviceInfo {
            val modsArr = d.optJSONArray("modules")
            val mods = buildList {
                if (modsArr != null) for (i in 0 until modsArr.length()) add(modsArr.optString(i))
            }
            val evidence = CapabilityEvidence.fromJson(d.optJSONObject("evidence"))
            return DeviceInfo(
                board = d.optString("board", "device"),
                chip = d.optString("chip", ""),
                fw = d.optString("fw", ""),
                modules = mods,
                battery = d.optInt("battery", -1),
                caps = DeviceCaps.fromJson(d.optJSONObject("caps")).withoutProvedAbsent(evidence),
                features = DeviceFeatures.fromJson(d.optJSONObject("features")),
                themeRgb565 = d.optInt("theme", 0),
                ledRgb = d.optInt("led", 0),
                ledBright = d.optInt("ledBright", -1),
                ledEffect = d.optInt("ledEffect", 0),
                screenBright = d.optInt("bright", -1),
                proto = (d.opt("proto") as? Int)?.takeIf { it >= 0 },
                build = DeviceBuild.fromJson(d.optJSONObject("build")),
                evidence = evidence,
                rxMax = app.brucia.protocol.FrameBudget.reported(d.opt("rx_max")),
                rxBuf = (d.opt("rx_buf") as? Int)?.takeIf { it > 0 },
            )
        }
    }
}
