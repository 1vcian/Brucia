package app.brucia

import app.brucia.ble.BleDevice
import app.brucia.report.ReportData
import app.brucia.report.SessionReport
import app.brucia.subghz.DecodedSignal
import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.ProbeRequest
import app.brucia.wardrive.Station
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReportTest {

    // Deterministic fake vendor resolver so the test needs no Android assets.
    private val vendorOf: (String) -> String? = { mac ->
        when {
            mac.startsWith("AA") -> "Apple, Inc."
            mac.startsWith("BB") -> "Samsung Electronics"
            mac.startsWith("CC") -> "Espressif Inc."
            else -> null
        }
    }

    private fun sample() = ReportData(
        fw = "0.4.0",
        board = "t-embed-cc1101",
        aps = listOf(
            ApObservation("AA:11:22:33:44:55", "HomeNet", 6, -45, "wpa2", null, null, 0L),
            ApObservation("BB:11:22:33:44:55", "", 11, -80, "open", null, null, 0L),
            ApObservation("CC:11:22:33:44:55", "IoTHub", 1, -60, "wpa3", null, null, 0L),
        ),
        stations = listOf(Station("AA:99:88:77:66:55", "AA:11:22:33:44:55", -50, 6, 4, 0L)),
        probes = listOf(ProbeRequest("BB:99:88:77:66:55", "MyHomeWifi", -55, 3, 0L)),
        ble = listOf(BleDevice("AA:aa:bb:cc:dd:ee", "iPhone", -40, 0L)),
        subghz = listOf(
            DecodedSignal("Princeton", 24, "ABCDEF", 350, 5, classification = "fixed"),
            DecodedSignal("KeeLoq", 66, "112233", 400, 2, classification = "rolling", serialHex = "DEADBE"),
        ),
        monitorFrames = 42,
        mitmTxns = 7,
    )

    @Test fun markdownHasPopulatedSectionsOnly() {
        val md = SessionReport.markdown(sample(), vendorOf)
        assertTrue(md.contains("# Brucia session report"))
        assertTrue(md.contains("fw 0.4.0"))
        assertTrue(md.contains("## Wi-Fi access points (3)"))
        assertTrue(md.contains("Apple, Inc."))
        assertTrue(md.contains("## Wi-Fi client stations (1)"))
        assertTrue(md.contains("## Client probe requests (1)"))
        assertTrue(md.contains("## Sub-GHz signals (2)"))
        assertTrue(md.contains("rolling"))
        assertTrue(md.contains("## BLE devices (1)"))
        assertTrue(md.contains("## Packet monitor"))
        assertTrue(md.contains("Frames captured: 42"))
        assertTrue(md.contains("## MITM proxy"))
        assertTrue(md.contains("## Insights (heuristic)"))
        // Empty sections must be omitted.
        assertFalse(md.contains("## Handshakes"))
        assertFalse(md.contains("## NRF24"))
    }

    @Test fun recoveredWifiKeyIsShownInPlaintext() {
        // DT01 (authorized decision): a recovered key is kept unmasked in the report. This test pins
        // that decision so a future change that masks it is caught deliberately.
        val d = sample().copy(crackResult = "hunter2-correcthorse")
        val md = SessionReport.markdown(d, vendorOf)
        assertTrue(md.contains("## Handshakes / PMKID"))
        assertTrue("key not shown verbatim", md.contains("Crack result: hunter2-correcthorse"))
        assertFalse("key must not be masked", md.contains("****"))
        assertTrue(SessionReport.insights(d, vendorOf).any { it.contains("A Wi-Fi key was recovered") })
    }

    @Test fun insightsAreHeuristicAndCoverKeyAngles() {
        val ins = SessionReport.insights(sample(), vendorOf)
        assertTrue(ins.any { it.contains("1 open, 0 WEP, 1 WPA2, 1 WPA3") })
        assertTrue(ins.any { it.contains("hidden SSID") })
        assertTrue(ins.any { it.contains("Strongest AP") })
        assertTrue(ins.any { it.contains("platform guess") && it.contains("Apple") })
        assertTrue(ins.any { it.contains("Privacy risk") && it.contains("MyHomeWifi") })
        assertTrue(ins.any { it.contains("1 fixed-code") && it.contains("1 rolling-code") })
    }

    @Test fun emptyDataProducesNoInsights() {
        assertTrue(SessionReport.insights(ReportData(), vendorOf).isEmpty())
        assertTrue(SessionReport.markdown(ReportData(), vendorOf).contains("Nothing captured yet"))
    }
}
