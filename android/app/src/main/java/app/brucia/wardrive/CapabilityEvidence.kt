package app.brucia.wardrive

import org.json.JSONObject

/**
 * Per-module evidence from the optional CAPS `d.evidence` object (see proto/PROTOCOL.md).
 * It explains `d.caps`; it does not replace it. The firmware never reports a physical test:
 * the highest level is "initialized" (driver brought up this session).
 */
enum class EvidenceLevel(val wire: String) {
    NONE("none"),
    COMPILED("compiled"),
    CONFIGURED("configured"),
    PROBED("probed"),
    INITIALIZED("initialized");

    companion object {
        fun fromWire(value: Any?): EvidenceLevel? = entries.firstOrNull { it.wire == value }
    }
}

/** Outcome of the electronic presence check; null when no check exists or none ran. */
enum class ProbeResult(val wire: String) {
    PRESENT("present"),
    ABSENT("absent"),
    BUSY("busy");

    companion object {
        fun fromWire(value: Any?): ProbeResult? = entries.firstOrNull { it.wire == value }
    }
}

/** A static pin finding: this module's [role] uses [gpio], which [with] also uses as [withRole]. */
data class PinConflict(val gpio: Int, val role: String, val with: String, val withRole: String) {
    fun text(): String = "GPIO$gpio $role shared with $with $withRole"
}

data class ModuleEvidence(
    val level: EvidenceLevel,
    val probe: ProbeResult? = null,
    val detail: String? = null,
    val conflicts: List<PinConflict> = emptyList(),
)

data class CapabilityEvidence(val modules: Map<String, ModuleEvidence>) {
    operator fun get(module: String): ModuleEvidence? = modules[module]

    /** True only when the firmware ran a probe for [module] and no chip answered. */
    fun provedAbsent(module: String): Boolean = modules[module]?.probe == ProbeResult.ABSENT

    /** Display rows in a stable order: known modules first, then any other reported module. */
    fun rows(): List<EvidenceRow> {
        val known = DISPLAY_ORDER.filter { it in modules }
        val rest = modules.keys.filter { it !in DISPLAY_ORDER }.sorted()
        return (known + rest).map { name ->
            val ev = modules.getValue(name)
            EvidenceRow(moduleLabel(name), statusText(ev), ev.detail, ev.conflicts.map { it.text() })
        }
    }

    companion object {
        private const val MAX_MODULES = 32
        private const val MAX_CONFLICTS = 16
        private const val MAX_DETAIL = 96
        private val NAME = Regex("[a-z0-9_]{1,24}")

        val DISPLAY_ORDER = listOf(
            "cc1101", "nfc", "nrf24", "fm", "lora", "eth", "ir", "mic", "speaker", "ibutton", "sd", "wifi", "ble",
        )

        /** Printable ASCII only, trimmed and bounded; null when nothing usable remains. */
        private fun clean(value: Any?, max: Int): String? {
            val s = value as? String ?: return null
            val t = s.filter { it in ' '..'~' }.trim().take(max).trim()
            return t.ifEmpty { null }
        }

        private fun name(value: Any?): String? = (value as? String)?.takeIf { NAME.matches(it) }

        /**
         * Parse `d.evidence`. Absent or malformed -> null (older firmware: nothing is claimed).
         * Entries without a known level are dropped; unknown probe values are ignored.
         */
        fun fromJson(obj: JSONObject?): CapabilityEvidence? {
            if (obj == null) return null
            val out = LinkedHashMap<String, ModuleEvidence>()
            for (key in obj.keys()) {
                if (out.size >= MAX_MODULES) break
                if (!NAME.matches(key)) continue
                val m = obj.optJSONObject(key) ?: continue
                val level = EvidenceLevel.fromWire(m.opt("level")) ?: continue
                val conflicts = buildList {
                    val arr = m.optJSONArray("conflict")
                    if (arr != null) for (i in 0 until arr.length()) {
                        if (size >= MAX_CONFLICTS) break
                        val c = arr.optJSONObject(i) ?: continue
                        val gpio = c.opt("gpio") as? Int ?: continue
                        if (gpio !in 0..127) continue
                        val role = name(c.opt("role")) ?: continue
                        val with = name(c.opt("with")) ?: continue
                        val withRole = name(c.opt("with_role")) ?: continue
                        add(PinConflict(gpio, role, with, withRole))
                    }
                }
                out[key] = ModuleEvidence(
                    level = level,
                    probe = ProbeResult.fromWire(m.opt("probe")),
                    detail = clean(m.opt("detail"), MAX_DETAIL),
                    conflicts = conflicts,
                )
            }
            return if (out.isEmpty()) null else CapabilityEvidence(out)
        }

        fun moduleLabel(module: String): String = when (module) {
            "cc1101" -> "CC1101 Sub-GHz"
            "nfc" -> "NFC reader"
            "nrf24" -> "NRF24L01"
            "fm" -> "Si4713 FM"
            "lora" -> "LoRa"
            "eth" -> "W5500 Ethernet"
            "ir" -> "IR"
            "mic" -> "Microphone"
            "speaker" -> "Speaker"
            "ibutton" -> "iButton"
            "sd" -> "SD card"
            "wifi" -> "Wi-Fi"
            "ble" -> "BLE"
            else -> module
        }

        /** One-line status for the device info list. Never says "tested". */
        fun statusText(ev: ModuleEvidence): String = when {
            ev.probe == ProbeResult.ABSENT -> "Probed: not detected"
            ev.probe == ProbeResult.BUSY -> "Not probed (bus busy)"
            else -> when (ev.level) {
                EvidenceLevel.NONE -> "Not built for this board"
                EvidenceLevel.COMPILED -> "Compiled in"
                EvidenceLevel.CONFIGURED -> "Configured, not probed"
                EvidenceLevel.PROBED -> "Detected by probe"
                EvidenceLevel.INITIALIZED -> "Driver initialized"
            }
        }

        /**
         * Why a module-gated tool is unavailable, from evidence; null when evidence is missing
         * (older firmware) or shows the module present, so the caller keeps its generic message.
         */
        fun unavailableReason(cap: String?, evidence: CapabilityEvidence?): String? {
            val ev = cap?.let { evidence?.get(it) } ?: return null
            val base = when {
                ev.probe == ProbeResult.ABSENT ->
                    "Checked when companion mode started: not detected" + (ev.detail?.let { " ($it)." } ?: ".")
                ev.probe == ProbeResult.BUSY ->
                    "Not probed: the bus was busy. Re-enter companion mode on the device to retry."
                ev.level == EvidenceLevel.NONE -> "This firmware build has no support for it on this board."
                ev.level == EvidenceLevel.COMPILED -> "Supported by this firmware, but no pins are configured on the device."
                ev.level == EvidenceLevel.CONFIGURED ->
                    "Pins are configured, but presence is not confirmed" + (ev.detail?.let { " ($it)." } ?: ".")
                else -> return null
            }
            return if (ev.conflicts.isEmpty()) base
            else base + " Pin conflict: " + ev.conflicts.joinToString("; ") { it.text() } + "."
        }

        /** Short form for the greyed tool card ("Needs X - <reason>"); null keeps the generic hint. */
        fun shortReason(cap: String?, evidence: CapabilityEvidence?): String? {
            val ev = cap?.let { evidence?.get(it) } ?: return null
            return when {
                ev.probe == ProbeResult.ABSENT -> "probed, not detected"
                ev.probe == ProbeResult.BUSY -> "not probed (bus busy)"
                ev.level == EvidenceLevel.NONE -> "not built for this board"
                ev.level == EvidenceLevel.COMPILED -> "pins not configured"
                ev.level == EvidenceLevel.CONFIGURED -> "configured, not confirmed"
                else -> null
            }
        }
    }
}

/** One device-info row: label, status, optional detail and conflict lines. */
data class EvidenceRow(val label: String, val status: String, val detail: String?, val conflicts: List<String>)
