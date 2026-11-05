package app.brucia

import app.brucia.device.CliParse
import app.brucia.ir.IrButton
import app.brucia.ir.IrParser
import app.brucia.subghz.OokSamples
import app.brucia.subghz.SubGhzDecoder
import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.ChannelAdvisor
import app.brucia.wardrive.GpxExport
import app.brucia.wardrive.KmlExport
import app.brucia.wardrive.SecurityFindings
import app.brucia.wardrive.WigleCsv
import app.brucia.wardrive.computeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {

    private fun ap(
        bssid: String, ssid: String = "s", ch: Int = 6, rssi: Int = -60,
        enc: String = "wpa2", lat: Double? = null, lon: Double? = null,
    ) = ApObservation(bssid, ssid, ch, rssi, enc, lat, lon, 0L)

    // --- exports ---

    @Test fun kmlEscapesXml() {
        val kml = KmlExport.build(listOf(ap("A", ssid = "A&B<C>\"", lat = 1.0, lon = 2.0)))
        assertTrue(kml.contains("&amp;"))
        assertTrue(kml.contains("&lt;"))
        assertFalse(kml.contains("A&B<C>")) // raw unescaped must not appear
    }

    @Test fun kmlSkipsApsWithoutGps() {
        val kml = KmlExport.build(listOf(ap("A"), ap("B", lat = 1.0, lon = 2.0)))
        assertEquals(1, Regex("<Placemark>").findAll(kml).count())
    }

    @Test fun wigleHasHeaderAndRows() {
        val csv = WigleCsv.build(listOf(ap("AA:BB", ch = 11, rssi = -40), ap("CC:DD")))
        val lines = csv.trim().split("\n")
        assertTrue(lines[0].startsWith("WigleWifi"))
        assertTrue(lines[1].startsWith("MAC,SSID,AuthMode"))
        assertEquals(4, lines.size) // 2 header + 2 rows
    }

    @Test fun gpxHasTrackpoints() {
        val gpx = GpxExport.build(listOf(1.0 to 2.0, 3.0 to 4.0))
        assertEquals(2, Regex("<trkpt").findAll(gpx).count())
        assertTrue(gpx.contains("creator=\"Brucia\""))
    }

    @Test fun gpxEmptyIsValid() {
        val gpx = GpxExport.build(emptyList())
        assertTrue(gpx.contains("<gpx"))
        assertEquals(0, Regex("<trkpt").findAll(gpx).count())
    }

    // --- IR parser ---

    @Test fun irPadsAndTruncatesAddress() {
        val b = IrParser.parse("name: X\ntype: parsed\nprotocol: NEC\naddress: 4\ncommand: 8")
        assertEquals(1, b.size)
        assertEquals("40000000", b[0].address)
        assertEquals("80000000", b[0].command)
    }

    @Test fun irIgnoresIncompleteBlocks() {
        val b = IrParser.parse("name: NoCode\ntype: parsed\nprotocol: NEC")
        assertTrue(b.isEmpty())
    }

    @Test fun irButtonRawCli() {
        val raw = IrButton("r", "", "", "", raw = true, frequency = 38000, data = "100 200")
        // One quoted samples word: SimpleCLI rejects a third unquoted word for `tx_raw`.
        assertEquals("ir tx_raw 38000 \"100 200\"", raw.toCli())
    }

    // --- CLI parse ---

    @Test fun cliFieldCaseInsensitiveAndMissing() {
        assertEquals("433", CliParse.field("FREQUENCY: 433", "frequency"))
        assertNull(CliParse.field("x: 1", "frequency"))
    }

    @Test fun rfReplayNeedsAllFields() {
        assertNull(CliParse.rfReplayCommand("Key: 11\nTe: 350")) // no frequency
        assertEquals(
            "rf tx 11 433920000 350 10",
            CliParse.rfReplayCommand("Key: 11\nFrequency: 433920000\nTe: 350"),
        )
    }

    // --- sub-ghz ---

    @Test fun subghzRejectsNoise() {
        val noise = intArrayOf(5, 7, 3, 9, 2, 8, 4) // implausible tiny pulses
        assertTrue(SubGhzDecoder.decode(noise).isEmpty())
    }

    @Test fun subghzSampleRepeats() {
        val out = SubGhzDecoder.decode(OokSamples.princeton24)
        assertTrue(out.isNotEmpty())
        assertTrue(out[0].repeats >= 1)
        assertTrue(out[0].teMicros in 250..500)
    }

    // --- analytics ---

    @Test fun channelAdvisorNoWifiReturnsOne() {
        assertEquals(1, ChannelAdvisor.recommend24(emptyList()))
        // only 5GHz -> treated as no 2.4GHz
        assertEquals(1, ChannelAdvisor.recommend24(listOf(ap("A", ch = 36))))
    }

    @Test fun congestionCounts() {
        val c = ChannelAdvisor.congestion(listOf(ap("A", ch = 1), ap("B", ch = 1), ap("C", ch = 6)))
        assertEquals(2, c[1])
        assertEquals(1, c[6])
    }

    @Test fun findingsEmptyForNone() {
        assertTrue(SecurityFindings.analyze(emptyList()).isEmpty())
    }

    @Test fun findingsSortedHighFirst() {
        val f = SecurityFindings.analyze(
            listOf(ap("A", enc = "open"), ap("B", enc = "wpa", ssid = ""), ap("C", enc = "wpa2"))
        )
        assertTrue(f.isNotEmpty())
        assertEquals("high", f.first().severity)
    }

    @Test fun statsSortedBreakdowns() {
        val st = computeStats(
            listOf(ap("A", ch = 6, enc = "wpa2"), ap("B", ch = 6, enc = "wpa2"), ap("C", ch = 1, enc = "open"))
        )
        assertEquals(3, st.total)
        assertEquals("wpa2", st.byEnc.first().first) // most common first
        assertEquals(1, st.byChannel.first().first)  // channels ascending
        assertEquals(1, st.openCount)
    }
}
