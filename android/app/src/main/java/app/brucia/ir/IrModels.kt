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
}
