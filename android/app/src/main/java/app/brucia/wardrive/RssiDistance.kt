package app.brucia.wardrive

import kotlin.math.pow

/** Rough distance estimate from RSSI using the log-distance path-loss model.
 *  d = 10^((txPowerAt1m - rssi) / (10 * n)). Indoor n ~ 3; txPower ~ -40 dBm @ 1m. */
object RssiDistance {
    fun meters(rssi: Int, txPowerAt1m: Int = -40, pathLossN: Double = 3.0): Double {
        if (rssi == 0) return -1.0
        return 10.0.pow((txPowerAt1m - rssi) / (10.0 * pathLossN))
    }

    /** Short human label, e.g. "~2 m" / "~45 m". */
    fun label(rssi: Int): String {
        val d = meters(rssi)
        if (d < 0) return "?"
        return if (d < 10) "~%.1f m".format(d) else "~%.0f m".format(d)
    }
}
