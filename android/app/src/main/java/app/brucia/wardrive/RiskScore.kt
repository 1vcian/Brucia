package app.brucia.wardrive

/** Heuristic per-network risk score (0-100) to prioritize targets. Phone-side. */
object RiskScore {
    data class Risk(val score: Int, val level: String) // "high" | "medium" | "low"

    private val defaultSsid = Regex(
        "(?i)(TIM|WINDTRE|FASTWEB|Vodafone|TP-?Link|TELINET|FRITZ|D-?Link|Netgear|Linksys|Tenda|Huawei)[-_ ]?\\w*"
    )

    fun of(ap: ApObservation): Risk {
        val e = ap.enc.lowercase()
        var s = when {
            e == "open" -> 90
            e == "wep" -> 85
            e.contains("wpa3") -> 10
            e == "wpa2" || e == "wpa2-ent" -> 30
            e.contains("wpa") -> 55 // legacy or mixed wpa/wpa2
            else -> 40
        }
        if (defaultSsid.matches(ap.ssid)) s += 10
        s = s.coerceIn(0, 100)
        val level = when {
            s >= 70 -> "high"
            s >= 40 -> "medium"
            else -> "low"
        }
        return Risk(s, level)
    }
}
