package app.brucia.subghz

import org.json.JSONObject

/**
 * What the firmware's additive `symbols`/`max_symbols` fields say about one `subghz.raw` burst.
 * They only describe the device receive buffer; none of these values proves that every on-air
 * pulse was captured, and none of them claims proved loss.
 */
enum class RawCompleteness {
    /** Fields absent (older firmware) or inconsistent: no completeness information. */
    NO_INFO,
    /** `symbols < max_symbols`: the burst was not cut by the receive buffer. */
    NOT_BUFFER_CUT,
    /** `symbols == max_symbols`: the buffer was full, so the burst may have been longer. */
    UNKNOWN_AT_LIMIT,
}

/** One parsed `EVENT subghz.raw` payload (see proto/PROTOCOL.md, Streams). */
data class SubGhzRawEvent(
    val freqHz: Long,
    val timings: IntArray,
    val symbols: Int?,
    val maxSymbols: Int?,
) {
    val completeness: RawCompleteness
        get() = completenessOf(symbols, maxSymbols)

    override fun equals(other: Any?): Boolean = other is SubGhzRawEvent && freqHz == other.freqHz &&
        timings.contentEquals(other.timings) && symbols == other.symbols && maxSymbols == other.maxSymbols

    override fun hashCode(): Int = ((freqHz.hashCode() * 31 + timings.contentHashCode()) * 31 +
        (symbols ?: -1)) * 31 + (maxSymbols ?: -1)

    companion object {
        /** Null when `timings` is missing; malformed metadata degrades to "no information". */
        fun parse(data: JSONObject): SubGhzRawEvent? {
            val arr = data.optJSONArray("timings") ?: return null
            val timings = IntArray(arr.length()) { arr.optInt(it) }
            var symbols = count(data, "symbols")
            var max = count(data, "max_symbols")
            if (symbols == null || max == null || max <= 0 || symbols > max) { symbols = null; max = null }
            return SubGhzRawEvent(data.optLong("freq", 0L), timings, symbols, max)
        }

        fun completenessOf(symbols: Int?, maxSymbols: Int?): RawCompleteness = when {
            symbols == null || maxSymbols == null || maxSymbols <= 0 || symbols !in 0..maxSymbols -> RawCompleteness.NO_INFO
            symbols < maxSymbols -> RawCompleteness.NOT_BUFFER_CUT
            else -> RawCompleteness.UNKNOWN_AT_LIMIT
        }

        /** A non-negative integral JSON number, else null (strings, fractions, negatives). */
        private fun count(data: JSONObject, key: String): Int? {
            val n = data.opt(key) as? Number ?: return null
            val d = n.toDouble()
            if (d.isNaN() || d < 0 || d > Int.MAX_VALUE || d != Math.floor(d)) return null
            return d.toInt()
        }
    }
}
