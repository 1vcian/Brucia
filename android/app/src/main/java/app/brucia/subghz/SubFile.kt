package app.brucia.subghz

/**
 * Parse and generate Flipper Zero `.sub` files (also read/written by the Bruce
 * firmware). Two forms are supported:
 *
 *   RAW:
 *     Filetype: Flipper SubGhz RAW File
 *     Version: 1
 *     Frequency: 433920000
 *     Preset: FuriHalSubGhzPresetOok650Async
 *     Protocol: RAW
 *     RAW_Data: 350 -1050 1050 -350 ...      (signed us; +high, -low; many lines)
 *
 *   Protocol based:
 *     Filetype: Flipper SubGhz Key File
 *     Version: 1
 *     Frequency: 433920000
 *     Preset: FuriHalSubGhzPresetOok650Async
 *     Protocol: Princeton
 *     Bit: 24
 *     Key: 00 00 00 00 00 A5 B3 C7
 *     TE: 350
 *
 * The in-app model is [SubSignal]. Generation is the inverse of parsing, so a
 * file round-trips.
 */
data class SubSignal(
    val frequencyHz: Long,
    val preset: String,
    /** "RAW" or a Flipper protocol name (e.g. "Princeton", "CAME", "Nice FLO"). */
    val protocol: String,
    /** RAW form: signed microsecond durations (+ = high/mark, - = low/space). */
    val rawData: IntArray = IntArray(0),
    /** Protocol form: payload width in bits. */
    val bits: Int = 0,
    /** Protocol form: key as uppercase hex (no separators). */
    val keyHex: String = "",
    /** Base timing element in us (0 = unspecified). */
    val te: Int = 0,
    val filetype: String = DEFAULT_FILETYPE_KEY,
    val version: Int = 1
) {
    val isRaw: Boolean get() = protocol.equals("RAW", ignoreCase = true)

    /** Absolute (unsigned) mark/space stream, as the decoder expects. */
    fun rawAbs(): IntArray = IntArray(rawData.size) { kotlin.math.abs(rawData[it]) }

    companion object {
        const val DEFAULT_PRESET = "FuriHalSubGhzPresetOok650Async"
        const val DEFAULT_FILETYPE_RAW = "Flipper SubGhz RAW File"
        const val DEFAULT_FILETYPE_KEY = "Flipper SubGhz Key File"
    }
}

object SubFile {

    /** Parse a `.sub` file body. Returns null if it is not a recognisable file. */
    fun parse(text: String): SubSignal? {
        var filetype = ""
        var version = 1
        var frequency = 0L
        var preset = SubSignal.DEFAULT_PRESET
        var protocol = ""
        var bits = 0
        var keyHex = ""
        var te = 0
        val raw = ArrayList<Int>()

        for (lineRaw in text.lineSequence()) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            // "Version 1" (no colon) is tolerated, as the firmware writes it.
            val (keyPart, valuePart) = if (idx >= 0) {
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            } else {
                val sp = line.indexOf(' ')
                if (sp < 0) continue
                line.substring(0, sp).trim() to line.substring(sp + 1).trim()
            }
            when (keyPart.lowercase()) {
                "filetype" -> filetype = valuePart
                "version" -> version = valuePart.toIntOrNull() ?: 1
                "frequency" -> frequency = valuePart.toLongOrNull() ?: 0L
                "preset" -> preset = valuePart
                "protocol" -> protocol = valuePart
                "bit" -> bits = valuePart.toIntOrNull() ?: 0
                "key" -> keyHex = valuePart.replace(" ", "").uppercase()
                "te" -> te = valuePart.toIntOrNull() ?: 0
                "raw_data", "data_raw" ->
                    valuePart.split(Regex("\\s+")).forEach { tok ->
                        tok.toIntOrNull()?.let { raw.add(it) }
                    }
            }
        }
        if (filetype.isEmpty() && protocol.isEmpty() && raw.isEmpty()) return null
        return SubSignal(
            frequencyHz = frequency,
            preset = preset,
            protocol = protocol.ifEmpty { if (raw.isNotEmpty()) "RAW" else "" },
            rawData = raw.toIntArray(),
            bits = bits,
            keyHex = keyHex,
            te = te,
            filetype = filetype.ifEmpty {
                if (raw.isNotEmpty()) SubSignal.DEFAULT_FILETYPE_RAW else SubSignal.DEFAULT_FILETYPE_KEY
            },
            version = version
        )
    }

    /** Generate the `.sub` file body for [sig]. Inverse of [parse]. */
    fun generate(sig: SubSignal): String {
        val sb = StringBuilder()
        sb.append("Filetype: ").append(sig.filetype).append('\n')
        sb.append("Version: ").append(sig.version).append('\n')
        sb.append("Frequency: ").append(sig.frequencyHz).append('\n')
        sb.append("Preset: ").append(sig.preset).append('\n')
        if (sig.isRaw) {
            sb.append("Protocol: RAW\n")
            // Flipper limits RAW_Data to 512 values per line.
            var count = 0
            var first = true
            for (v in sig.rawData) {
                if (count % 512 == 0) {
                    if (!first) sb.append('\n')
                    sb.append("RAW_Data:")
                    first = false
                }
                sb.append(' ').append(v)
                count++
            }
            sb.append('\n')
        } else {
            sb.append("Protocol: ").append(sig.protocol).append('\n')
            sb.append("Bit: ").append(sig.bits).append('\n')
            sb.append("Key: ").append(formatKey(sig.keyHex)).append('\n')
            if (sig.te > 0) sb.append("TE: ").append(sig.te).append('\n')
        }
        return sb.toString()
    }

    /**
     * Build a `.sub` model from a decoded signal (protocol based when the protocol
     * is known, else RAW from [rawAbs]). [rawAbs] is the captured unsigned
     * mark/space stream used when the signal has no protocol identity.
     */
    fun fromDecoded(sig: DecodedSignal, frequencyHz: Long, rawAbs: IntArray = IntArray(0)): SubSignal {
        val known = OokProtocols.byAnyName(sig.flipperProtocol) ?: OokProtocols.byCanonical(sig.protocol)
        if (sig.classification == "fixed" && known != null) {
            return SubSignal(
                frequencyHz = frequencyHz,
                preset = SubSignal.DEFAULT_PRESET,
                protocol = known.flipper,
                bits = sig.bits,
                keyHex = sig.codeHex.uppercase(),
                te = sig.teMicros,
                filetype = SubSignal.DEFAULT_FILETYPE_KEY
            )
        }
        return fromRawAbs(if (rawAbs.isNotEmpty()) rawAbs else SubGhzEncoder.encode(
            OokProtocols.byCanonical("Princeton")!!, sig.codeHex, sig.bits, sig.teMicros, 3
        ), frequencyHz)
    }

    /** Build a RAW `.sub` model from an unsigned mark/space stream. */
    fun fromRawAbs(markSpace: IntArray, frequencyHz: Long): SubSignal {
        // Even index = mark (high, +), odd index = space (low, -).
        val signed = IntArray(markSpace.size) { i ->
            if (i % 2 == 0) markSpace[i] else -markSpace[i]
        }
        return SubSignal(
            frequencyHz = frequencyHz,
            preset = SubSignal.DEFAULT_PRESET,
            protocol = "RAW",
            rawData = signed,
            filetype = SubSignal.DEFAULT_FILETYPE_RAW
        )
    }

    /**
     * The device CLI command that transmits [sig], or null when it cannot be
     * transmitted over the serial path (RAW that does not decode to a known
     * protocol). [repeat] frames are sent.
     *
     * Protocol-based `.sub` -> `subghz txp <name> <freq> <bits> <key> <te> <rep>`
     * (resolves the firmware RF registry, round-trips with the decoder). RAW is
     * first re-decoded on the phone; if a fixed protocol is recovered the same
     * txp path is used, otherwise there is no USB push and TX is unavailable.
     */
    fun transmitCommand(sig: SubSignal, repeat: Int = 10): String? {
        if (!sig.isRaw) {
            if (sig.keyHex.isEmpty() || sig.bits <= 0) return null
            val name = OokProtocols.byAnyName(sig.protocol)?.canonical ?: sig.protocol
            val te = if (sig.te > 0) sig.te else 0
            return "subghz txp $name ${sig.frequencyHz} ${sig.bits} ${sig.keyHex} $te $repeat"
        }
        // RAW: try to recover a fixed protocol from the timings.
        val decoded = SubGhzDecoder.decode(sig.rawAbs())
            .firstOrNull { it.classification == "fixed" } ?: return null
        val name = OokProtocols.byAnyName(decoded.flipperProtocol)?.canonical ?: decoded.protocol
        return "subghz txp $name ${sig.frequencyHz} ${decoded.bits} ${decoded.codeHex} ${decoded.teMicros} $repeat"
    }

    /** One-line human summary for the UI. */
    fun summary(sig: SubSignal): String {
        val mhz = sig.frequencyHz / 1_000_000.0
        return if (sig.isRaw) {
            "RAW - ${sig.rawData.size} edges @ ${"%.3f".format(mhz)} MHz"
        } else {
            "${sig.protocol} - ${sig.bits} bits, key 0x${sig.keyHex} @ ${"%.3f".format(mhz)} MHz"
        }
    }

    private fun formatKey(keyHex: String): String {
        // Flipper keys are 8 space-separated bytes, left padded with zero bytes.
        val clean = keyHex.uppercase().ifEmpty { "0" }
        val padded = clean.padStart(16, '0')
        return padded.chunked(2).joinToString(" ")
    }
}
