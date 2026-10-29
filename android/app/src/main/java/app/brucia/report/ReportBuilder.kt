package app.brucia.report

import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.ChannelAdvisor
import app.brucia.wardrive.SecurityFindings
import app.brucia.wardrive.computeStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a Markdown security-assessment report from the collected session data. */
object ReportBuilder {

    fun markdown(
        aps: List<ApObservation>,
        nfcRead: String?,
        nfcFindings: List<app.brucia.wardrive.Finding>,
        bleCount: Int,
        crackResult: String?,
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("# Brucia security assessment\n\n")
        sb.append("Generated: ").append(ts).append("  \n")
        sb.append("For authorized security research and education only.\n\n")

        sb.append("## Summary\n\n")
        sb.append("- Wi-Fi access points: ").append(aps.size).append('\n')
        sb.append("- BLE devices: ").append(bleCount).append('\n')
        sb.append("- WPA crack result: ").append(crackResult ?: "n/a").append("\n\n")

        if (aps.isNotEmpty()) {
            val st = computeStats(aps)
            sb.append("## Wi-Fi\n\n")
            sb.append("- Open networks: ").append(st.openCount).append('\n')
            sb.append("- Geotagged: ").append(st.withGps).append('\n')
            sb.append("- Busiest channel: ").append(st.mostCongestedChannel ?: "-").append('\n')
            sb.append("- Suggested 2.4GHz channel: ").append(ChannelAdvisor.recommend24(aps)).append('\n')
            sb.append("\n### Encryption\n\n")
            for ((enc, n) in st.byEnc) sb.append("- ").append(enc).append(": ").append(n).append('\n')
            sb.append("\n### Findings\n\n")
            val f = SecurityFindings.analyze(aps)
            if (f.isEmpty()) sb.append("- none\n") else for (x in f) sb.append("- [").append(x.severity).append("] ").append(x.title).append(" - ").append(x.detail).append('\n')
            sb.append("\n### Top vendors\n\n")
            for ((v, n) in st.topVendors) sb.append("- ").append(v).append(": ").append(n).append('\n')
        }

        if (nfcRead != null || nfcFindings.isNotEmpty()) {
            sb.append("\n## NFC / RFID\n\n")
            for (x in nfcFindings) sb.append("- [").append(x.severity).append("] ").append(x.title).append(" - ").append(x.detail).append('\n')
            if (nfcRead != null) sb.append("\n```\n").append(nfcRead.take(1000)).append("\n```\n")
        }

        sb.append("\n---\nBrucia - companion to Bruce, AGPL-3.0.\n")
        return sb.toString()
    }
}
