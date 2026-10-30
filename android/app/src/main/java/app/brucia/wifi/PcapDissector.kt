package app.brucia.wifi

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Pure-JVM core of the on-phone PCAP dissect screen: turns classic-pcap bytes ([PcapReader]) into a
 * summary plus a bounded packet list ([Dot11Frame] per record). No Android dependencies, so it is
 * unit-testable; the screen only does the file picking and rendering.
 */
object PcapDissector {
    const val MAX_ROWS = 1000                          // packet-list rows kept (summary counts all)
    const val TOP_BSSIDS = 8                           // BSSIDs shown in the summary
    const val MAX_CAPTURE_BYTES = 32 * 1024 * 1024     // refuse captures larger than 32 MiB
    const val CHECK_EVERY = 256                        // records between cancellation checks

    /** One row in the packet list (kept small so a big capture doesn't blow up memory). */
    data class Row(val n: Int, val label: String, val src: String, val dst: String, val bssid: String, val eapol: Boolean)

    data class Summary(
        val frames: Int, val mgmt: Int, val ctrl: Int, val data: Int, val eapol: Int,
        val bssids: List<Pair<String, Int>>, // top BSSIDs by frame count desc, then BSSID asc
    )

    data class Result(val summary: Summary, val rows: List<Row>)

    /**
     * Dissect a capture. Returns null when [PcapReader] yields no records (bad magic, empty or
     * truncated before the first record). [checkActive] runs every [CHECK_EVERY] records; if it
     * throws (e.g. `ensureActive()` on a cancelled coroutine) the dissection aborts with that exception.
     */
    fun dissect(bytes: ByteArray, checkActive: () -> Unit = {}): Result? {
        val recs = PcapReader.parse(bytes)
        if (recs.isEmpty()) return null
        var mgmt = 0; var ctrl = 0; var dataC = 0; var eapol = 0
        val bssidCount = HashMap<String, Int>()
        val rows = ArrayList<Row>(minOf(recs.size, MAX_ROWS))
        recs.forEachIndexed { i, r ->
            if (i % CHECK_EVERY == 0) checkActive()
            val info = Dot11Frame.parse(r.frame)
            when (info.type) { 0 -> mgmt++; 1 -> ctrl++; 2 -> dataC++ }
            if (info.isEapol) eapol++
            if (info.addr3.isNotBlank()) bssidCount[info.addr3] = (bssidCount[info.addr3] ?: 0) + 1
            if (rows.size < MAX_ROWS) rows.add(Row(i + 1, info.label, info.addr2, info.addr1, info.addr3, info.isEapol))
        }
        val top = bssidCount.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_BSSIDS).map { it.key to it.value }
        return Result(Summary(recs.size, mgmt, ctrl, dataC, eapol, top), rows)
    }

    /**
     * Read [input] fully, but throw [IOException] as soon as more than [MAX_CAPTURE_BYTES] arrive,
     * so an oversized capture is never buffered whole. Exactly [MAX_CAPTURE_BYTES] is accepted.
     */
    fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_CAPTURE_BYTES) throw IOException("Capture exceeds ${MAX_CAPTURE_BYTES / (1024 * 1024)} MiB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
