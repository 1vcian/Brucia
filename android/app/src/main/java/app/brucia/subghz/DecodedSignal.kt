package app.brucia.subghz

/**
 * Result of decoding a captured OOK (on-off keying) sub-GHz transmission.
 *
 * All heavy lifting happens on the phone: the ESP device only streams the raw
 * pulse timings captured from its RF receiver, and this app turns them into a
 * protocol name plus the actual transmitted code.
 */
data class DecodedSignal(
    /** Human readable protocol name, e.g. "Princeton", "CAME", "NICE_FLO", "KeeLoq", "Unknown". */
    val protocol: String,
    /** Number of data bits that make up the decoded frame. */
    val bits: Int,
    /** The decoded data payload rendered as uppercase hex (zero padded to the bit width). */
    val codeHex: String,
    /** Detected base timing element TE (the short pulse) in microseconds. */
    val teMicros: Int,
    /** How many times this identical frame was seen in the capture. */
    val repeats: Int,
    /**
     * Code class:
     *   "fixed"   - static code, always the same, trivially replayable.
     *   "rolling" - KeeLoq / HCS301-style hopping code; cannot simply be replayed.
     *   "unknown" - undetermined (generic tristate / learn fallback).
     */
    val classification: String = "fixed",
    /**
     * Flipper-compatible protocol name (as written to `Protocol:` in a `.sub`
     * file). Equals [protocol] when the canonical name is already Flipper-style.
     */
    val flipperProtocol: String = protocol,
    /** For rolling codes: the fixed device serial (hex), if extractable. */
    val serialHex: String = "",
    /** For rolling codes: the button / command nibble, or -1 if unknown. */
    val button: Int = -1,
    /** Human readable note (e.g. the rolling-code replay warning, tristate hint). */
    val note: String = ""
) {
    /** Alias kept for clarity: the transmitted key as hex. Same value as [codeHex]. */
    val keyHex: String get() = codeHex
}
