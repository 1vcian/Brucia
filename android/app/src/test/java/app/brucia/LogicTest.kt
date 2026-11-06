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
import org.junit.Assert.assertFalse
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
    fun rfReplayOnlyForCapturesRfTxReproduces() {
        // `rf tx` sends protocol 1 with 24 bits: the firmware's headless capture format.
        val rcswitch = "Frequency: 433920000\nPreset: 0\nProtocol: RcSwitch\nBit: 24\nKey: 44 55 33\nTE: 350"
        assertEquals("rf tx 445533 433920000 350 10", CliParse.rfReplayCommand(rcswitch))
        // Another protocol or bit length would go out as a different frame: no replay command.
        assertEquals(null, CliParse.rfReplayCommand(rcswitch.replace("Protocol: RcSwitch", "Protocol: CAME")))
        assertEquals(null, CliParse.rfReplayCommand(rcswitch.replace("Bit: 24", "Bit: 12")))
        assertEquals(null, CliParse.rfReplayCommand(rcswitch.replace("Protocol: RcSwitch", "Protocol: KeeLoq")))
        // The display form "Protocol: <name>(<preset>)" is matched by name.
        assertEquals("rf tx 445533 433920000 350 10",
            CliParse.rfReplayCommand(rcswitch.replace("Protocol: RcSwitch", "Protocol: Princeton(0)")))
    }

    /** Backlog A4: items saved before the replay guard keep their text but are not replayed. */
    @Test
    fun storedRfPayloadReplayableOnlyWhenRfTxReproducesIt() {
        val cmd = "rf tx 445533 433920000 350 10"
        assertTrue(CliParse.rfPayloadReplayable(cmd, "RcSwitch"))
        assertTrue(CliParse.rfPayloadReplayable(cmd, "Princeton(0)"))
        assertTrue(CliParse.rfPayloadReplayable(cmd, ""))   // protocol not recorded
        assertTrue(CliParse.rfPayloadReplayable(cmd, null)) // geo pin
        assertTrue(CliParse.rfPayloadReplayable("rf tx 0000000000445533 433920000 350 10", null))
        // Old saves: another protocol was stored with an `rf tx` command.
        assertFalse(CliParse.rfPayloadReplayable(cmd, "CAME"))
        assertFalse(CliParse.rfPayloadReplayable(cmd, "KeeLoq"))
        assertFalse(CliParse.rfPayloadReplayable(cmd, "Nice FLO(1)"))
        // A key wider than 24 bits cannot be the 24-bit frame `rf tx` sends.
        assertFalse(CliParse.rfPayloadReplayable("rf tx 1445533 433920000 350 10", null))
        assertFalse(CliParse.rfPayloadReplayable("rf tx 00000123456789AB 433920000 350 10", "RcSwitch"))
        // Not a replay command: raw capture text, malformed or IR.
        assertFalse(CliParse.rfPayloadReplayable("Frequency: 433920000\nProtocol: CAME", "CAME"))
        assertFalse(CliParse.rfPayloadReplayable("rf tx zz 433920000 350 10", null))
        assertFalse(CliParse.rfPayloadReplayable("rf tx 445533", null))
        assertFalse(CliParse.rfPayloadReplayable("ir tx NEC 0x04 0x08", null))
        // Every command the current capture path builds stays replayable with its own protocol.
        val fresh = "Frequency: 433920000\nProtocol: RcSwitch\nBit: 24\nKey: 44 55 33\nTE: 350"
        assertTrue(CliParse.rfPayloadReplayable(CliParse.rfReplayCommand(fresh)!!, "RcSwitch"))
    }

    @Test
    fun geoReplayRefusesAnRfPinRfTxCannotReproduce() {
        assertEquals(app.brucia.wardrive.GeoReplayService.NOT_REPRODUCIBLE,
            app.brucia.wardrive.GeoReplayService.precheck("rf tx 00000123456789AB 433920000 350 10"))
        assertEquals(null, app.brucia.wardrive.GeoReplayService.precheck("rf tx 445533 433920000 350 10"))
        assertEquals(null, app.brucia.wardrive.GeoReplayService.precheck("ir tx NEC 0x04 0x08"))
        assertEquals(app.brucia.wardrive.GeoReplayService.NOTHING, app.brucia.wardrive.GeoReplayService.precheck(" "))
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
