package app.brucia.wardrive

/**
 * Lightweight 2.4GHz channel planning over observed APs. All computation is
 * phone-side; the ESP only captures the observations.
 */
object ChannelAdvisor {

    /** Returns a map of channel number -> number of APs seen on that channel. */
    fun congestion(aps: Collection<ApObservation>): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (ap in aps) {
            counts[ap.channel] = (counts[ap.channel] ?: 0) + 1
        }
        return counts
    }

    /**
     * Recommends the least congested non-overlapping 2.4GHz channel among
     * {1, 6, 11}. Only APs on channels 1..14 are considered. Each AP adds an
     * interference weight based on channel distance to the candidate:
     *   distance 0        -> 1.0
     *   distance 1..4     -> 1 - distance / 5 (linearly decreasing)
     *   distance >= 5     -> 0.0
     * The candidate with the lowest total score wins; ties prefer the lower
     * channel because {1, 6, 11} is iterated in order. Returns 1 when there are
     * no 2.4GHz APs.
     */
    fun recommend24(aps: Collection<ApObservation>): Int {
        val band24 = aps.filter { it.channel in 1..14 }
        if (band24.isEmpty()) return 1

        val candidates = intArrayOf(1, 6, 11)
        var best = candidates[0]
        var bestScore = Double.MAX_VALUE

        for (candidate in candidates) {
            var score = 0.0
            for (ap in band24) {
                val dist = kotlin.math.abs(ap.channel - candidate)
                score += when {
                    dist == 0 -> 1.0
                    dist <= 4 -> 1.0 - dist / 5.0
                    else -> 0.0
                }
            }
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }
}
