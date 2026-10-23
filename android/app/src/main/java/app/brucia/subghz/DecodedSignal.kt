package app.brucia.subghz

/**
 * Result of decoding a captured OOK (on-off keying) sub-GHz transmission.
 *
 * All heavy lifting happens on the phone: the ESP device only streams the raw
 * pulse timings captured from its RF receiver, and this app turns them into a
 * protocol name plus the actual transmitted code.
 */
data class DecodedSignal(
    /** Human readable protocol name, e.g. "Princeton", "PT2262/EV1527", "CAME", "NICE", "Unknown". */
    val protocol: String,
    /** Number of data bits that make up the decoded frame. */
    val bits: Int,
    /** The decoded data payload rendered as uppercase hex (zero padded to the bit width). */
    val codeHex: String,
    /** Detected base timing element TE (the short pulse) in microseconds. */
    val teMicros: Int,
    /** How many times this identical frame was seen in the capture. */
    val repeats: Int
)
