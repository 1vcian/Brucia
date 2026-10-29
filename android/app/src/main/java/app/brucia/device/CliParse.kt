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

    /** Best-effort replay command for a captured sub-GHz signal, or null. */
    fun rfReplayCommand(out: String): String? {
        val key = (field(out, "Key") ?: field(out, "key"))?.replace(" ", "") ?: return null
        val freqRaw = field(out, "Frequency") ?: field(out, "freq") ?: return null
        val freq = freqRaw.filter { it.isDigit() }
        val te = (field(out, "Te") ?: field(out, "te"))?.filter { it.isDigit() } ?: return null
        if (key.isEmpty() || freq.isEmpty() || te.isEmpty()) return null
        return "rf tx $key $freq $te 10"
    }
}
