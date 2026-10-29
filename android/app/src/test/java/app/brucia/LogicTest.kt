package app.brucia

import app.brucia.device.CliParse
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
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test
    fun subghzDecodesSample() {
        val out = SubGhzDecoder.decode(OokSamples.princeton24)
        assertTrue("expected at least one decode", out.isNotEmpty())
        assertEquals(24, out[0].bits)
        assertEquals("A5B3C7", out[0].codeHex)
    }

    @Test
    fun subghzHandlesEmpty() {
        assertTrue(SubGhzDecoder.decode(IntArray(0)).isEmpty())
    }

    @Test
    fun irParsesFlipperFormat() {
        val text = """
            Filetype: IR signals file
            Version: 1
            #
            name: Power
            type: parsed
            protocol: NEC
            address: 04 00 00 00
            command: 08 00 00 00
            #
            name: Vol_up
            type: parsed
            protocol: NECext
            address: 00 DF 00 00
            command: 1C E3 00 00
        """.trimIndent()
        val b = IrParser.parse(text)
        assertEquals(2, b.size)
        assertEquals("Power", b[0].name)
        assertEquals("NEC", b[0].protocol)
        assertEquals("04000000", b[0].address)
        assertEquals("08000000", b[0].command)
        assertEquals("ir tx NEC 04000000 08000000", b[0].toCli())
    }

    @Test
    fun cliParseFieldsAndReplay() {
        val out = "Frequency: 433920000\nPreset: ook\nProtocol: Princeton\nKey: 44 55 33\nTe: 350"
        assertEquals("Princeton", CliParse.field(out, "Protocol"))
        assertEquals("rf tx 445533 433920000 350 10", CliParse.rfReplayCommand(out))
        assertEquals(null, CliParse.rfReplayCommand("nothing useful"))
    }

    @Test
    fun exportsProduceValidHeaders() {
        val aps = listOf(
            ApObservation("AA:BB:CC:DD:EE:FF", "Net1", 6, -50, "wpa2", 45.0, 9.0, 1L),
            ApObservation("11:22:33:44:55:66", "Open,Net", 1, -80, "open", null, null, 2L),
        )
        val csv = WigleCsv.build(aps)
        assertTrue(csv.startsWith("WigleWifi"))
        assertTrue(csv.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(csv.contains("\"Open,Net\"")) // comma quoted

        val kml = KmlExport.build(aps)
        assertTrue(kml.contains("<kml"))
        assertTrue(kml.contains("9.0,45.0,0")) // lon,lat,0 order

        val gpx = GpxExport.build(listOf(45.0 to 9.0, 45.1 to 9.1))
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("lat=\"45.1\""))
    }

    @Test
    fun channelAdvisorPicksLeastCongested() {
        // crowd channel 1; expect 6 or 11 recommended
        val aps = (1..10).map { ApObservation("M$it", "s", 1, -60, "wpa2", null, null, 0L) }
        val best = ChannelAdvisor.recommend24(aps)
        assertTrue(best == 6 || best == 11)
    }

    @Test
    fun statsCountsAndFindings() {
        val aps = listOf(
            ApObservation("A", "s1", 1, -50, "open", null, null, 0L),
            ApObservation("B", "s2", 6, -60, "wpa2", null, null, 0L),
            ApObservation("C", "s3", 6, -70, "wep", null, null, 0L),
        )
        val st = computeStats(aps)
        assertEquals(3, st.total)
        assertEquals(1, st.openCount)
        assertEquals(6, st.mostCongestedChannel)
        val findings = SecurityFindings.analyze(aps)
        assertTrue(findings.any { it.severity == "high" }) // open + wep
    }
}
