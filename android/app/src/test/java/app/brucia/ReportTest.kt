package app.brucia

import app.brucia.report.ReportBuilder
import app.brucia.wardrive.ApObservation
import app.brucia.nfc.NfcSecurity
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    @Test fun reportHasSections() {
        val aps = listOf(
            ApObservation("AA", "Open1", 6, -50, "open", 45.0, 9.0, 0L),
            ApObservation("BB", "Net2", 1, -70, "wpa2", null, null, 0L),
        )
        val nfc = "UID: 04 A2\nType: MIFARE Classic 1K\nSAK: 08"
        val md = ReportBuilder.markdown(aps, nfc, NfcSecurity.analyze(nfc), 3, "password123")
        assertTrue(md.contains("# Brucia security assessment"))
        assertTrue(md.contains("## Wi-Fi"))
        assertTrue(md.contains("Open networks: 1"))
        assertTrue(md.contains("## NFC"))
        assertTrue(md.contains("Classic"))
        assertTrue(md.contains("password123"))
    }
}
