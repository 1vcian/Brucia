package app.brucia.protocol

/**
 * Size limits of frames the app sends (proto/PROTOCOL.md "Inbound frame capacity (rx_max)").
 *
 * The device's serial RX buffer drops bytes once it is full and the companion loop is busy, so a
 * frame is only reliable when it fits whole. CAPS `rx_max` is the largest frame payload (bytes
 * after the 4-byte length prefix) the device's buffer holds. Firmware without the field is
 * assumed to have the smallest buffer measured on hardware for PSRAM boards (1024 bytes) minus
 * the firmware's headroom: [LEGACY_RX_MAX]. Chunked transfers (uploads, speaker/FM PCM) size each
 * frame to the budget; single commands are refused above a reported `rx_max` instead of being
 * lost on the wire.
 *
 * Pure Kotlin so the rules are testable on the JVM.
 */
object FrameBudget {
    /** COMPANION_MAX_FRAME: the firmware never accepts a larger payload. */
    const val MAX_FRAME = 8192

    /** Assumed inbound capacity of firmware that predates CAPS `rx_max` (1024 - 256 headroom). */
    const val LEGACY_RX_MAX = 768

    /** Firmware upload::MAX_CHUNK (src/companion/storage_upload.h). */
    const val UPLOAD_MAX_CHUNK = 3072

    /**
     * Envelope bytes around a body with the longest request id:
     * {"v":0,"t":3,"id":-2147483648,"d":{...}} minus the body itself.
     */
    val ENVELOPE_OVERHEAD = """{"v":0,"t":3,"id":-2147483648,"d":}""".length

    /** CAPS `rx_max` when present and in range, else null (older firmware). */
    fun reported(value: Any?): Int? = (value as? Int)?.takeIf { it in 1..MAX_FRAME }

    /** Payload budget for chunked transfers: reported `rx_max`, else [LEGACY_RX_MAX]. */
    fun chunkBudget(rxMax: Int?): Int = rxMax ?: LEGACY_RX_MAX

    /** Payload limit for any single frame: reported `rx_max`, else the protocol maximum. */
    fun sendLimit(rxMax: Int?): Int = rxMax ?: MAX_FRAME

    /**
     * Upper bound of [s]'s UTF-8 length inside a JSON string after escaping: quotes and
     * backslashes take two bytes, control characters six. '/' stays one byte because
     * [CompanionClient] undoes Android org.json's optional "\/" escape before sending.
     */
    fun escapedLength(s: String): Int {
        var n = 0
        for (c in s) n += when {
            c == '"' || c == '\\' -> 2
            c < ' ' -> 6
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
        return n
    }

    /** Upper bound of the payload of CMD {"cmd": [command]}. */
    fun cmdPayload(command: String): Int = ENVELOPE_OVERHEAD + """{"cmd":""}""".length + escapedLength(command)

    /**
     * End index (exclusive) of the next upload chunk starting at [offset]: the largest range of at
     * most [UPLOAD_MAX_CHUNK] bytes whose `fs_upload_chunk <token> <offset> <base64>` frame fits
     * [budget]. Returns [offset] when not even 3 bytes fit (the caller reports it).
     */
    fun uploadChunkEnd(bytes: ByteArray, offset: Int, budget: Int, token: String,
                       encode: (ByteArray) -> String): Int {
        val prefix = "fs_upload_chunk $token $offset "
        val room = budget - cmdPayload(prefix)
        var raw = minOf(UPLOAD_MAX_CHUNK, bytes.size - offset, (room / 4) * 3).coerceAtLeast(0)
        while (raw > 0) {
            val b64 = encode(bytes.copyOfRange(offset, offset + raw))
            val excess = cmdPayload(prefix + b64) - budget
            if (excess <= 0) return offset + raw
            // Defensive (base64 needs no escaping): drop raw bytes for the excess, at least one group.
            raw -= maxOf(3, (excess + 3) / 4 * 3)
        }
        return offset
    }

    /**
     * Samples per PCM data frame whose base64 field sits in a body of [bodyOverhead] bytes (keys
     * and command name, without the base64). 2 bytes per sample, base64 4/3. [default] when the
     * device does not report `rx_max` (firmware that predates it keeps today's frame size).
     */
    fun pcmFrameSamples(default: Int, rxMax: Int?, bodyOverhead: Int): Int {
        if (rxMax == null) return default
        val b64Room = rxMax - ENVELOPE_OVERHEAD - bodyOverhead
        val bytes = (b64Room / 4) * 3
        return (bytes / 2).coerceIn(1, default)
    }
}
