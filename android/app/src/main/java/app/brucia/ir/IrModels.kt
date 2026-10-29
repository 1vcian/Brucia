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
        if (raw) "ir tx_raw $frequency $data"
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
}
