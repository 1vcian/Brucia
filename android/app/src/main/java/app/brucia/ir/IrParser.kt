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

    /** A tiny starter set so the screen is not empty; load a .ir file for more. */
    val samples = listOf(
        IrButton("Sample NEC - Power", "NEC", "04000000", "08000000"),
        IrButton("Sample NEC - Vol +", "NEC", "04000000", "02000000"),
        IrButton("Sample NEC - Vol -", "NEC", "04000000", "03000000"),
    )
}
