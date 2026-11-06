package app.brucia.report

import app.brucia.ble.BleDevice
import app.brucia.subghz.DecodedSignal
import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.Nrf24Device
import app.brucia.wardrive.Nrf24MjDevice
import app.brucia.wardrive.OuiLookup
import app.brucia.wardrive.ProbeRequest
import app.brucia.wardrive.Station
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Immutable snapshot of everything captured in a session, assembled by the ViewModel and
 * turned into a Markdown report and a heuristic insights summary by [SessionReport].
 */
data class ReportData(
    val fw: String? = null,
    val board: String? = null,
    val modules: List<String> = emptyList(),
    /** Firmware provenance lines from CAPS (see DeviceInfo.provenanceLines); empty when unknown. */
    val provenance: List<String> = emptyList(),
    /** App + device Diagnostics lines (see wardrive.Diagnostics.exportLines); empty to omit. */
    val diagnostics: List<String> = emptyList(),
    val aps: List<ApObservation> = emptyList(),
    val stations: List<Station> = emptyList(),
    val probes: List<ProbeRequest> = emptyList(),
    val ble: List<BleDevice> = emptyList(),
    val subghz: List<DecodedSignal> = emptyList(),
    val nrf24: List<Nrf24Device> = emptyList(),
    val nrf24Mj: List<Nrf24MjDevice> = emptyList(),
    val handshakeInfo: String? = null,
    val crackResult: String? = null,
    val monitorFrames: Int = 0,
    val mitmTxns: Int = 0,
)

/**
 * Builds the session report (Markdown) and a heuristic insights summary from [ReportData].
 *
 * Both are pure functions of the data plus a [vendorOf] resolver (defaulting to the bundled
 * OUI table), so they can be unit-tested without an Android context. Insights are plain
 * rule-of-thumb aggregation, honestly labelled as heuristic - there is no ML here.
 */
object SessionReport {

    fun markdown(d: ReportData, vendorOf: (String) -> String? = OuiLookup::vendor): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("# Brucia session report\n\n")
        sb.append("Generated: ").append(ts).append("  \n")
        if (d.fw != null || d.board != null) {
            sb.append("Device: ").append(d.board ?: "?").append(" / fw ").append(d.fw ?: "?")
            if (d.modules.isNotEmpty()) sb.append(" / ").append(d.modules.joinToString(", "))
            sb.append("  \n")
        }
        for (line in d.provenance) sb.append(mdCell(line)).append("  \n")
        sb.append("For authorized security research and education only.\n")

        if (d.aps.isNotEmpty()) {
            sb.append("\n## Wi-Fi access points (").append(d.aps.size).append(")\n\n")
            sb.append("| BSSID | SSID | Ch | Enc | RSSI | Vendor |\n")
            sb.append("|---|---|---|---|---|---|\n")
            for (ap in d.aps.sortedByDescending { it.rssi }) {
                sb.append("| ").append(ap.bssid)
                    .append(" | ").append(mdCell(ap.ssid.ifBlank { "(hidden)" }))
                    .append(" | ").append(ap.channel)
                    .append(" | ").append(ap.enc.ifBlank { "?" })
                    .append(" | ").append(ap.rssi)
                    .append(" | ").append(mdCell(vendorOf(ap.bssid) ?: "-"))
                    .append(" |\n")
            }
        }

        if (d.stations.isNotEmpty()) {
            sb.append("\n## Wi-Fi client stations (").append(d.stations.size).append(")\n\n")
            for (s in d.stations) {
                sb.append("- ").append(s.mac)
                    .append(" (").append(vendorOf(s.mac) ?: "unknown vendor").append(")")
                    .append(" bssid ").append(s.bssid.ifBlank { "?" })
                    .append(", ch ").append(s.ch).append(", rssi ").append(s.rssi)
                    .append(", x").append(s.count).append('\n')
            }
        }

        if (d.probes.isNotEmpty()) {
            sb.append("\n## Client probe requests (").append(d.probes.size).append(")\n\n")
            for (p in d.probes.sortedByDescending { it.count }) {
                val ssid = if (p.ssid.isBlank()) "(broadcast)" else p.ssid
                sb.append("- ").append(p.mac)
                    .append(" (").append(vendorOf(p.mac) ?: "unknown vendor").append(")")
                    .append(" seeks \"").append(mdCell(ssid)).append("\"")
                    .append(", rssi ").append(p.rssi).append(", x").append(p.count).append('\n')
            }
        }

        val handshakeLines = buildList {
            d.handshakeInfo?.let { add("Captured/loaded: ${mdCell(it)}") }
            d.crackResult?.let { add("Crack result: $it") }
        }
        if (handshakeLines.isNotEmpty()) {
            sb.append("\n## Handshakes / PMKID\n\n")
            for (l in handshakeLines) sb.append("- ").append(l).append('\n')
        }

        if (d.subghz.isNotEmpty()) {
            sb.append("\n## Sub-GHz signals (").append(d.subghz.size).append(")\n\n")
            for (s in d.subghz) {
                sb.append("- ").append(mdCell(s.protocol))
                    .append(" [").append(s.classification).append("]")
                    .append(", ").append(s.bits).append(" bits, key ").append(s.codeHex)
                    .append(", TE ").append(s.teMicros).append("us, x").append(s.repeats)
                if (s.classification == "rolling" && s.serialHex.isNotBlank()) {
                    sb.append(" (serial ").append(s.serialHex).append(")")
                }
                sb.append('\n')
            }
        }

        if (d.ble.isNotEmpty()) {
            sb.append("\n## BLE devices (").append(d.ble.size).append(")\n\n")
            for (b in d.ble.sortedByDescending { it.rssi }) {
                sb.append("- ").append(b.mac)
                    .append(" (").append(vendorOf(b.mac) ?: "unknown vendor").append(")")
                    .append(" \"").append(mdCell(b.name.ifBlank { "(no name)" })).append("\"")
                    .append(", rssi ").append(b.rssi).append('\n')
            }
        }

        if (d.nrf24.isNotEmpty() || d.nrf24Mj.isNotEmpty()) {
            sb.append("\n## NRF24 (2.4GHz)\n\n")
            for (n in d.nrf24) {
                sb.append("- ESB ").append(n.addr).append(", ch ").append(n.ch)
                    .append(", rssi ").append(n.rssi).append(", x").append(n.count).append('\n')
            }
            for (m in d.nrf24Mj) {
                sb.append("- MouseJack ").append(m.addr).append(" (").append(m.type)
                    .append("), ch ").append(m.ch).append('\n')
            }
        }

        if (d.monitorFrames > 0) {
            sb.append("\n## Packet monitor\n\n")
            sb.append("- Frames captured: ").append(d.monitorFrames).append('\n')
        }

        if (d.mitmTxns > 0) {
            sb.append("\n## MITM proxy\n\n")
            sb.append("- Intercepted transactions: ").append(d.mitmTxns).append('\n')
        }

        sb.append("\n## Insights (heuristic)\n\n")
        sb.append("Rule-of-thumb analysis - not machine learning. Treat as hints, verify before acting.\n\n")
        val insights = insights(d, vendorOf)
        if (insights.isEmpty()) sb.append("- Nothing captured yet.\n")
        else for (i in insights) sb.append("- ").append(i).append('\n')

        if (d.diagnostics.isNotEmpty()) {
            sb.append("\n## Diagnostics\n\n")
            for (line in d.diagnostics) sb.append("- ").append(mdCell(line)).append('\n')
        }

        sb.append("\n---\nBrucia - companion to Bruce, AGPL-3.0.\n")
        return sb.toString()
    }

    /**
     * Heuristic insights computed from the captured data. Honestly labelled: these are simple
     * rules over OUI vendors / BLE names / encryption strings, not a model.
     */
    fun insights(d: ReportData, vendorOf: (String) -> String? = OuiLookup::vendor): List<String> {
        val out = ArrayList<String>()

        // Encryption mix.
        if (d.aps.isNotEmpty()) {
            var open = 0; var wep = 0; var wpa2 = 0; var wpa3 = 0; var other = 0; var hidden = 0
            for (ap in d.aps) {
                val e = ap.enc.trim().lowercase()
                when {
                    e == "open" || e.isBlank() -> open++
                    e.contains("wpa3") || e.contains("sae") -> wpa3++
                    e.contains("wpa2") -> wpa2++
                    e.contains("wep") -> wep++
                    else -> other++
                }
                if (ap.ssid.isBlank()) hidden++
            }
            out.add("APs by security: $open open, $wep WEP, $wpa2 WPA2, $wpa3 WPA3, $other other.")
            if (open > 0) out.add("$open open network(s) - unencrypted, higher exposure.")
            if (wep > 0) out.add("$wep WEP network(s) - deprecated and trivially broken.")
            if (hidden > 0) out.add("$hidden hidden SSID(s) detected (blank beacon name).")

            val strongest = d.aps.maxByOrNull { it.rssi }
            val weakest = d.aps.minByOrNull { it.rssi }
            if (strongest != null && weakest != null) {
                out.add("Strongest AP: ${strongest.ssid.ifBlank { "(hidden)" }} at ${strongest.rssi} dBm; " +
                    "weakest: ${weakest.ssid.ifBlank { "(hidden)" }} at ${weakest.rssi} dBm.")
            }
        }

        // Platform / vendor breakdown across APs, stations and BLE (OUI + BLE names).
        val platform = linkedMapOf("Apple" to 0, "Android" to 0, "IoT" to 0, "Other" to 0)
        fun tally(mac: String, name: String?) {
            val p = platformOf(vendorOf(mac), name)
            platform[p] = (platform[p] ?: 0) + 1
        }
        for (ap in d.aps) tally(ap.bssid, ap.ssid)
        for (s in d.stations) tally(s.mac, null)
        for (b in d.ble) tally(b.mac, b.name)
        if (platform.values.any { it > 0 }) {
            out.add("Device platform guess (from OUI + BLE names): " +
                platform.entries.filter { it.value > 0 }.joinToString(", ") { "${it.key} ${it.value}" } + ".")
        }

        // Top vendors overall.
        val vendors = HashMap<String, Int>()
        fun vt(mac: String) { val v = vendorOf(mac) ?: return; vendors[v] = (vendors[v] ?: 0) + 1 }
        for (ap in d.aps) vt(ap.bssid)
        for (s in d.stations) vt(s.mac)
        for (b in d.ble) vt(b.mac)
        val topV = vendors.entries.sortedByDescending { it.value }.take(3)
        if (topV.isNotEmpty()) out.add("Top vendors: " + topV.joinToString(", ") { "${it.key} (${it.value})" } + ".")

        // Privacy risk: clients probing for specific (named) networks reveal where they have been.
        val namedProbes = d.probes.filter { it.ssid.isNotBlank() }
        if (namedProbes.isNotEmpty()) {
            val ssids = namedProbes.map { it.ssid }.distinct()
            out.add("Privacy risk: ${namedProbes.size} client probe(s) leak named networks: " +
                ssids.take(6).joinToString(", ") + (if (ssids.size > 6) ", ..." else "") +
                ". A client broadcasting home/work SSIDs can be geolocated and profiled.")
        }

        // Privacy posture: randomized vs stable BLE identifiers. The locally-administered bit (0x02
        // of the first octet) marks a randomized MAC; a STABLE MAC can be tracked across time/place.
        if (d.ble.isNotEmpty()) {
            fun isRandomized(mac: String): Boolean {
                val firstOctet = mac.take(2).toIntOrNull(16) ?: return false
                return (firstOctet and 0x02) != 0
            }
            val stable = d.ble.count { it.mac.isNotBlank() && !isRandomized(it.mac) }
            val randomized = d.ble.size - stable
            if (stable > 0 || randomized > 0) {
                out.add("BLE identifiers: $randomized randomized (privacy-aware), $stable stable - " +
                    "stable MACs can be tracked across time and location (see the RF timeline).")
            }
        }

        // Sub-GHz rolling vs fixed.
        if (d.subghz.isNotEmpty()) {
            val rolling = d.subghz.count { it.classification == "rolling" }
            val fixed = d.subghz.count { it.classification == "fixed" }
            out.add("Sub-GHz: $fixed fixed-code (replayable), $rolling rolling-code (hopping, not trivially replayable).")
        }

        if (d.nrf24Mj.isNotEmpty()) out.add("${d.nrf24Mj.size} MouseJack-class 2.4GHz device(s) - potential HID injection targets.")
        if (d.crackResult != null) out.add("A Wi-Fi key was recovered this session - rotate it if this is your network.")

        return out
    }

    /** Coarse platform classification from an OUI vendor string and an optional advertised name. */
    private fun platformOf(vendor: String?, name: String?): String {
        val v = (vendor ?: "").lowercase()
        val n = (name ?: "").lowercase()
        val apple = listOf("apple")
        val appleName = listOf("iphone", "ipad", "ipod", "macbook", "airpods", "apple", "beats")
        val android = listOf("samsung", "google", "xiaomi", "oneplus", "huawei", "oppo", "vivo", "motorola", "htc", "lge", "lg electronics")
        val androidName = listOf("galaxy", "pixel", "android", "redmi", "oneplus")
        val iot = listOf("espressif", "raspberry", "tuya", "sonoff", "shelly", "tp-link", "amazon", "nest", "ring", "wyze", "ubiquiti", "sonos", "roku", "philips", "belkin", "d-link", "netgear")
        val iotName = listOf("esp_", "esp-", "shelly", "tasmota", "sonoff", "echo", "alexa", "nest", "govee", "smart")
        return when {
            apple.any { it in v } || appleName.any { it in n } -> "Apple"
            android.any { it in v } || androidName.any { it in n } -> "Android"
            iot.any { it in v } || iotName.any { it in n } -> "IoT"
            else -> "Other"
        }
    }

    /**
     * Escape the characters that would break a Markdown table cell or list item. Device-reported
     * text (SSIDs, BLE names) is attacker-chosen: a newline in it would start a new heading or
     * table row in the shared report.
     */
    internal fun mdCell(s: String): String = s.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
}
