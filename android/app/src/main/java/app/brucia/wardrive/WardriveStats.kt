package app.brucia.wardrive

/** Aggregate analytics computed on the phone from the collected access points. */
data class WardriveStats(
    val total: Int,
    val openCount: Int,
    val withGps: Int,
    val byEnc: List<Pair<String, Int>>,
    val byChannel: List<Pair<Int, Int>>,
    val mostCongestedChannel: Int?,
    val topVendors: List<Pair<String, Int>>,
)

/** Real offload work: breakdowns + OUI vendor resolution over the whole dataset. */
fun computeStats(aps: List<ApObservation>): WardriveStats {
    val enc = HashMap<String, Int>()
    val chan = HashMap<Int, Int>()
    val vendor = HashMap<String, Int>()
    var open = 0
    var gps = 0
    for (ap in aps) {
        enc[ap.enc] = (enc[ap.enc] ?: 0) + 1
        chan[ap.channel] = (chan[ap.channel] ?: 0) + 1
        if (ap.enc == "open") open++
        if (ap.lat != null && ap.lon != null) gps++
        val v = OuiResolver.vendor(ap.bssid) ?: "Unknown"
        vendor[v] = (vendor[v] ?: 0) + 1
    }
    return WardriveStats(
        total = aps.size,
        openCount = open,
        withGps = gps,
        byEnc = enc.toList().sortedByDescending { it.second },
        byChannel = chan.toList().sortedBy { it.first },
        mostCongestedChannel = chan.maxByOrNull { it.value }?.key,
        topVendors = vendor.toList().sortedByDescending { it.second }.take(8),
    )
}
