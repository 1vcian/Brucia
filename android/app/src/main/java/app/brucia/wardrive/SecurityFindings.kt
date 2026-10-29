package app.brucia.wardrive

/**
 * A single human-readable security observation about the scanned environment.
 * severity is one of "high", "medium", "info".
 */
data class Finding(
    val severity: String,
    val title: String,
    val detail: String,
)

/**
 * Derives security findings from a set of AP observations. All logic is
 * phone-side and purely heuristic; it never contacts any network.
 */
object SecurityFindings {

    // Matches common ISP / router default SSID prefixes (optionally followed by
    // a separator and some trailing characters).
    private val DEFAULT_NAME_REGEX =
        Regex("(?i)(TIM|WINDTRE|FASTWEB|Vodafone|TP-?Link|TELINET|FRITZ|D-?Link|Netgear|Linksys)[-_ ]?\\w*")

    // Ordering weight used to sort findings high -> medium -> info.
    private val SEVERITY_ORDER = mapOf("high" to 0, "medium" to 1, "info" to 2)

    private const val MAX_EXAMPLES = 3

    /** Returns findings sorted high -> medium -> info; empty if there are no APs. */
    fun analyze(aps: Collection<ApObservation>): List<Finding> {
        if (aps.isEmpty()) return emptyList()

        val findings = ArrayList<Finding>()

        val open = aps.filter { normEnc(it.enc) == "open" }
        if (open.isNotEmpty()) {
            findings.add(
                Finding(
                    severity = "high",
                    title = "Open networks",
                    detail = "${open.size} unencrypted network(s). " + examples(open),
                )
            )
        }

        val wep = aps.filter { normEnc(it.enc) == "wep" }
        if (wep.isNotEmpty()) {
            findings.add(
                Finding(
                    severity = "high",
                    title = "WEP networks",
                    detail = "${wep.size} network(s) using deprecated WEP. " + examples(wep),
                )
            )
        }

        // WPA (original) without WPA2 or WPA3 present in the enc string.
        val wpaOnly = aps.filter { ap ->
            val e = normEnc(ap.enc)
            e.contains("wpa") && !e.contains("wpa2") && !e.contains("wpa3")
        }
        if (wpaOnly.isNotEmpty()) {
            findings.add(
                Finding(
                    severity = "medium",
                    title = "Legacy WPA networks",
                    detail = "${wpaOnly.size} network(s) on WPA (not WPA2/WPA3). " + examples(wpaOnly),
                )
            )
        }

        val hidden = aps.filter { it.ssid.isBlank() }
        if (hidden.isNotEmpty()) {
            findings.add(
                Finding(
                    severity = "info",
                    title = "Hidden SSIDs",
                    detail = "${hidden.size} network(s) broadcasting no SSID.",
                )
            )
        }

        val defaults = aps.filter {
            it.ssid.isNotBlank() && DEFAULT_NAME_REGEX.matches(it.ssid)
        }
        if (defaults.isNotEmpty()) {
            findings.add(
                Finding(
                    severity = "info",
                    title = "Default router names",
                    detail = "${defaults.size} network(s) with vendor/ISP default-style SSID. " +
                        examples(defaults),
                )
            )
        }

        return findings.sortedBy { SEVERITY_ORDER[it.severity] ?: Int.MAX_VALUE }
    }

    /** Normalizes an encryption label to lower case for matching. */
    private fun normEnc(enc: String): String = enc.trim().lowercase()

    /** Builds a short "e.g. A, B, C" example list from AP SSIDs. */
    private fun examples(aps: List<ApObservation>): String {
        val names = aps.asSequence()
            .map { if (it.ssid.isBlank()) "(hidden)" else it.ssid }
            .distinct()
            .take(MAX_EXAMPLES)
            .toList()
        if (names.isEmpty()) return ""
        return "e.g. " + names.joinToString(", ")
    }
}
