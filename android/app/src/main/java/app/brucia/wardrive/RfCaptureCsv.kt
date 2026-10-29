package app.brucia.wardrive

/** Capture-to-CSV status surfaced to the UI: whether recording is on and how many rows so far. */
data class RfCaptureState(val capturing: Boolean, val samples: Int)

/** One captured subghz.raw event: when it arrived, its frequency, and the raw edge timings. */
data class RfCaptureRow(
    val timeMillis: Long,
    val freqHz: Long,
    val timings: IntArray,
)

/**
 * Build a CSV of captured Sub-GHz raw timing events (one row per subghz.raw event).
 * The edge timings are kept in a single space-separated, quoted column so the row layout
 * stays stable regardless of how many edges an event carries.
 */
object RfCaptureCsv {

    fun build(rows: Collection<RfCaptureRow>): String {
        val sb = StringBuilder()
        sb.append("time_ms,freq_hz,num_edges,timings_us\n")
        for (r in rows) {
            sb.append(r.timeMillis).append(',')
                .append(r.freqHz).append(',')
                .append(r.timings.size).append(',')
                .append('"').append(r.timings.joinToString(" ")).append('"')
                .append('\n')
        }
        return sb.toString()
    }
}
