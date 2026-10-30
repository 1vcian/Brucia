package app.brucia.ir

/**
 * Parses the universal Flipper `.ir` format into buttons. This is what makes the
 * catalog "universal": any Flipper-IRDB file can be loaded on the phone (a dataset
 * far too large for the device) and transmitted through it.
 */
object IrParser {

    fun parse(text: String): List<IrButton> {
        val out = ArrayList<IrButton>()
        var name = ""
        var type = ""
        var protocol = ""
        var address = ""
        var command = ""
        var freq = 38000
        var data = ""

        fun flush() {
            if (name.isNotEmpty()) {
                if (type == "raw" && data.isNotEmpty()) {
                    out.add(IrButton(name, "", "", "", raw = true, frequency = freq, data = data))
                } else if (address.isNotEmpty() && command.isNotEmpty()) {
                    out.add(IrButton(name, protocol, address, command))
                }
            }
            name = ""; type = ""; protocol = ""; address = ""; command = ""; freq = 38000; data = ""
        }

        for (lineRaw in text.lines()) {
            val line = lineRaw.trim()
            if (line.startsWith("#")) { flush(); continue }
            val idx = line.indexOf(':')
            if (idx < 0) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            when (k) {
                "name" -> { if (name.isNotEmpty()) flush(); name = v }
                "type" -> type = v
                "protocol" -> protocol = v
                "address" -> address = v.replace(" ", "").uppercase().padEnd(8, '0').take(8)
                "command" -> command = v.replace(" ", "").uppercase().padEnd(8, '0').take(8)
                "frequency" -> freq = v.toIntOrNull() ?: 38000
                "data" -> data = v
            }
        }
        flush()
        return out
    }

    /**
     * Parse the one-line `ir_rx` ACK the companion firmware sends instead of a `.ir` block:
     * "<protocol> addr=<hex bytes> cmd=<hex bytes>[ (raw: <value>)]" or "raw: <durations>".
     * Returns null for anything else (the caller shows the text as is).
     */
    fun parseRxSummary(text: String): IrButton? {
        val line = text.trim()
        if (line.startsWith("raw:")) {
            val data = line.removePrefix("raw:").trim()
            val valid = data.isNotEmpty() && data.split(' ').all { it.toIntOrNull()?.let { d -> d > 0 } == true }
            return if (valid) IrButton("Raw signal", "", "", "", raw = true, data = data) else null
        }
        val m = RX_SUMMARY.matchEntire(line) ?: return null
        val protocol = m.groupValues[1]
        val address = hexField(m.groupValues[2]) ?: return null
        val command = hexField(m.groupValues[3]) ?: return null
        return IrButton("$protocol ${m.groupValues[3].trim()}", protocol, address, command)
    }

    private val RX_SUMMARY = Regex("""^(\S+) addr=(.*?) cmd=(.*?)(?: \(raw: .*\))?$""")

    /** "04 00 00 00" -> "04000000" (same normalization as `.ir` fields); null if not hex. */
    private fun hexField(v: String): String? {
        val hex = v.replace(" ", "").uppercase()
        if (hex.isEmpty() || hex.length > 8 || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return hex.padEnd(8, '0')
    }

    /** A tiny starter set so the screen is not empty; load a .ir file for more. */
    val samples = listOf(
        IrButton("Sample NEC - Power", "NEC", "04000000", "08000000"),
        IrButton("Sample NEC - Vol +", "NEC", "04000000", "02000000"),
        IrButton("Sample NEC - Vol -", "NEC", "04000000", "03000000"),
    )
}
