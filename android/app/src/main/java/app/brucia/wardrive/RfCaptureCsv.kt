package app.brucia.wardrive

/**
 * Capture-to-CSV status surfaced to the UI: whether recording is on, how many rows so far, and
 * how many of them filled the device receive buffer (`symbols == max_symbols`), i.e. may have
 * been longer than what was stored. That count is "completeness unknown", never proved loss.
 */
data class RfCaptureState(val capturing: Boolean, val samples: Int, val atBufferLimit: Int = 0)

/**
 * One captured subghz.raw event: when it arrived, its frequency, the raw edge timings and the
 * optional firmware completeness metadata (null when the firmware did not send it).
 */
data class RfCaptureRow(
    val timeMillis: Long,
    val freqHz: Long,
    val timings: IntArray,
    val symbols: Int? = null,
    val maxSymbols: Int? = null,
)

/**
 * Build a CSV of captured Sub-GHz raw timing events (one row per subghz.raw event).
 * The edge timings are kept in a single space-separated, quoted column so the row layout
 * stays stable regardless of how many edges an event carries. `symbols`/`max_symbols` are
 * appended after the original four columns and left empty when the firmware omitted them.
 */
object RfCaptureCsv {

    fun build(rows: Collection<RfCaptureRow>): String {
        val sb = StringBuilder()
        sb.append("time_ms,freq_hz,num_edges,timings_us,symbols,max_symbols\n")
        for (r in rows) {
            sb.append(r.timeMillis).append(',')
                .append(r.freqHz).append(',')
                .append(r.timings.size).append(',')
                .append('"').append(r.timings.joinToString(" ")).append('"').append(',')
                .append(r.symbols?.toString() ?: "").append(',')
                .append(r.maxSymbols?.toString() ?: "")
                .append('\n')
        }
        return sb.toString()
    }
}
