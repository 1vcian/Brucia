package app.brucia.device

/** Small helpers to pull fields out of Bruce CLI text output. */
object CliParse {

    /** First "Key: value" style field matching [key] (case-insensitive), or null. */
    fun field(text: String, key: String): String? {
        for (line in text.lines()) {
            val l = line.trim()
            val idx = l.indexOf(':')
            if (idx > 0 && l.substring(0, idx).trim().equals(key, ignoreCase = true)) {
                return l.substring(idx + 1).trim()
            }
        }
        return null
    }

    /**
     * Replay command for a captured sub-GHz signal, or null when `rf tx` cannot reproduce it.
     * `rf tx <key> <freq> <te> <count>` always sends a 24-bit code with protocol 1 (RcSwitch /
     * Princeton timing, firmware rfTxCallback), so a capture decoded as another protocol (CAME, Nice,
     * KeeLoq, ...) or another bit length would be "replayed" as a different frame: no command then.
     */
    fun rfReplayCommand(out: String): String? {
        val protocol = field(out, "Protocol")?.substringBefore('(')?.trim()
        if (!protocol.isNullOrEmpty() && RF_TX_PROTOCOLS.none { it.equals(protocol, ignoreCase = true) }) return null
        val bits = field(out, "Bit")?.trim()
        if (!bits.isNullOrEmpty() && bits != RF_TX_BITS.toString()) return null
        val key = (field(out, "Key") ?: field(out, "key"))?.replace(" ", "") ?: return null
        val freqRaw = field(out, "Frequency") ?: field(out, "freq") ?: return null
        val freq = freqRaw.filter { it.isDigit() }
        val te = (field(out, "Te") ?: field(out, "te"))?.filter { it.isDigit() } ?: return null
        if (key.isEmpty() || freq.isEmpty() || te.isEmpty()) return null
        return "rf tx $key $freq $te 10"
    }

    /** Protocol names a capture may carry that `rf tx` (protocol 1, 24 bits) reproduces. */
    private val RF_TX_PROTOCOLS = listOf("RcSwitch", "RcSwitch_1", "Princeton")
    private const val RF_TX_BITS = 24
}
