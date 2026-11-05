package app.brucia.ir

/** One IR button. Parsed codes map to `ir tx`; raw ones to `ir tx_raw`. */
data class IrButton(
    val name: String,
    val protocol: String,
    val address: String,
    val command: String,
    val raw: Boolean = false,
    val frequency: Int = 38000,
    val data: String = "",
) {
    /** The Bruce CLI command that transmits this button. */
    fun toCli(): String =
        if (raw) cliRaw(frequency, data)
        else "ir tx $protocol $address $command"

    /**
     * Args for the fixed `ir_tx_raw <khz> <csv durations>` contract: carrier in kHz
     * (rounded from [frequency] Hz) and the durations as a comma-separated list.
     */
    fun toTxRawArgs(): Pair<Int, String> {
        val khz = ((frequency + 500) / 1000).coerceAtLeast(1)
        val csv = data.trim()
            .split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }
            .joinToString(",")
        return khz to csv
    }

    companion object {
        /**
         * Bruce's `ir tx_raw <frequency> <samples>` CLI line. `tx_raw` has exactly two positional
         * arguments and SimpleCLI (pinned rennancockles/SimpleCLI b23d3779) splits words on every
         * unquoted space (src/c/parser.c parse_words); a third word fails the parse with
         * "unknown argument" (src/c/cmd.c, no unset positional left). So the samples are one
         * double-quoted word; arg_set_value (src/c/arg.c) strips the quotes. Bruce's
         * sendRawCommand (src/modules/ir/custom_ir.cpp) then splits them on single spaces.
         */
        fun cliRaw(frequencyHz: Int, samples: String): String =
            "ir tx_raw $frequencyHz \"" +
                samples.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.joinToString(" ") + "\""

        /**
         * Bruce's CLI form of an `ir_tx_raw <khz> <csv durations>` frame, used when a LITE
         * firmware compiled the companion IR commands out.
         */
        fun cliTxRaw(khz: Int, durations: String): String = cliRaw(khz * 1000, durations)

        private val LEGACY_RAW = Regex("""^ir\s+tx_raw\s+(\d+)\s+([^"]+)$""")

        /**
         * Saved IR payloads written before [cliRaw] quoted the samples hold
         * `ir tx_raw <Hz> d1 d2 ...`, which Bruce's CLI rejects. Rewrite that legacy form into the
         * quoted one at replay time (no stored row changes). A single unquoted sample (valid as is),
         * already-quoted lines, `ir tx ...` and anything else are returned unchanged.
         */
        fun replayable(payload: String): String {
            val m = LEGACY_RAW.matchEntire(payload.trim()) ?: return payload
            val hz = m.groupValues[1].toIntOrNull() ?: return payload
            val samples = m.groupValues[2]
            return if (samples.trim().split(Regex("[\\s,]+")).count { it.isNotEmpty() } > 1) cliRaw(hz, samples) else payload
        }
    }
}
