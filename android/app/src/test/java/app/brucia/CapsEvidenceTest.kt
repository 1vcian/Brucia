package app.brucia

import app.brucia.wardrive.CapabilityEvidence
import app.brucia.wardrive.DeviceCaps
import app.brucia.wardrive.DeviceInfo
import app.brucia.wardrive.EvidenceLevel
import app.brucia.wardrive.ModuleEvidence
import app.brucia.wardrive.PinConflict
import app.brucia.wardrive.ProbeResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsEvidenceTest {
    private fun caps() = JSONObject()
        .put("cc1101", true).put("nfc", true).put("nrf24", false).put("fm", false)
        .put("ir", true).put("mic", true).put("speaker", true).put("lora", false)
        .put("eth", false).put("ibutton", false).put("wifi", true).put("ble", true)

    private fun body(evidence: JSONObject? = null) = JSONObject()
        .put("board", "Lilygo T-Embed CC1101").put("chip", "esp32-s3").put("fw", "0.1.3").put("proto", 0)
        .put("caps", caps())
        .apply { if (evidence != null) put("evidence", evidence) }

    private fun ev(level: String, probe: String? = null, detail: String? = null, conflict: JSONArray? = null) =
        JSONObject().put("level", level).apply {
            if (probe != null) put("probe", probe)
            if (detail != null) put("detail", detail)
            if (conflict != null) put("conflict", conflict)
        }

    private fun conflict(gpio: Any, role: String, with: String, withRole: String) =
        JSONObject().put("gpio", gpio).put("role", role).put("with", with).put("with_role", withRole)

    /** Shape the T-Embed firmware reports without add-ons (values from capabilities.cpp). */
    private fun tEmbedEvidence() = JSONObject()
        .put("wifi", ev("compiled", detail = "ESP32 SoC radio; driver starts per operation"))
        .put("cc1101", ev("probed", "present", "SPI PARTNUM 0x00 VERSION 0x14"))
        .put("nfc", ev("probed", "present", "PN532 I2C firmware IC 0x32 v1.6"))
        .put("nrf24", ev("configured", "absent", "SPI SETUP_AW readback invalid",
            JSONArray().put(conflict(44, "cs", "eth", "cs")).put(conflict(43, "ce", "eth", "int"))))
        .put("eth", ev("configured", "absent", "SPI VERSIONR 0x00 (W5500 = 0x04)",
            JSONArray().put(conflict(44, "cs", "nrf24", "cs"))))
        .put("fm", ev("configured", "absent", "no I2C ACK at 0x63"))
        .put("lora", ev("none"))
        .put("mic", ev("configured", detail = "board profile pins; no chip identification",
            conflict = JSONArray().put(conflict(39, "clk", "speaker", "mclk"))))
        .put("ibutton", ev("compiled", detail = "1-Wire answers only with a key present; not probed"))
        .put("sd", ev("probed", "present", "card mounted"))

    @Test fun olderFirmwareWithoutEvidenceKeepsCurrentGatingAndExplainsNothing() {
        val info = DeviceInfo.fromCaps(body())
        assertNull(info.evidence)
        assertEquals(DeviceCaps.fromJson(caps()), info.caps)
        assertNull(CapabilityEvidence.unavailableReason("nrf24", info.evidence))
        assertNull(CapabilityEvidence.shortReason("nrf24", info.evidence))
    }

    @Test fun parsesLevelsProbeDetailAndConflicts() {
        val e = DeviceInfo.fromCaps(body(tEmbedEvidence())).evidence!!
        assertEquals(ModuleEvidence(EvidenceLevel.PROBED, ProbeResult.PRESENT, "SPI PARTNUM 0x00 VERSION 0x14"), e["cc1101"])
        assertEquals(EvidenceLevel.COMPILED, e["wifi"]!!.level)
        assertNull(e["wifi"]!!.probe)
        assertEquals(EvidenceLevel.NONE, e["lora"]!!.level)
        assertEquals(
            listOf(PinConflict(44, "cs", "eth", "cs"), PinConflict(43, "ce", "eth", "int")),
            e["nrf24"]!!.conflicts,
        )
        assertEquals("GPIO39 clk shared with speaker mclk", e["mic"]!!.conflicts.single().text())
        assertTrue(e.provedAbsent("nrf24"))
        assertFalse(e.provedAbsent("cc1101"))
        assertFalse(e.provedAbsent("ibutton")) // configured/compiled is not a proved absence
    }

    @Test fun onlyProvedAbsenceDisablesAReportedCapability() {
        val evidence = JSONObject()
            .put("cc1101", ev("configured", "absent", "no CHIP_RDYn on MISO within 5 ms"))
            .put("nfc", ev("configured", "busy"))
            .put("ir", ev("configured"))
            .put("mic", ev("none"))
            .put("nrf24", ev("probed", "present")) // evidence never enables a false cap
        val info = DeviceInfo.fromCaps(body(evidence))
        assertFalse(info.caps.cc1101)
        assertTrue(info.caps.nfc)   // busy: not proof of absence
        assertTrue(info.caps.ir)    // configured, no probe
        assertTrue(info.caps.mic)   // inconsistent level alone does not change gating
        assertFalse(info.caps.nrf24)
        assertTrue(info.caps.speaker) // no evidence entry
        assertTrue(info.caps.wifi)
    }

    @Test fun malformedEvidenceIsIgnoredNotInvented() {
        assertNull(DeviceInfo.fromCaps(body().put("evidence", "probed")).evidence)
        assertNull(DeviceInfo.fromCaps(body().put("evidence", JSONArray())).evidence)
        assertNull(CapabilityEvidence.fromJson(JSONObject()))
        val e = CapabilityEvidence.fromJson(JSONObject()
            .put("cc1101", ev("tested", "present"))          // unknown level: dropped
            .put("nfc", JSONObject().put("probe", "absent"))  // no level: dropped
            .put("BAD KEY", ev("probed"))                      // invalid module name: dropped
            .put("eth", "probed")                              // not an object: dropped
            .put("fm", ev("configured", "maybe", "  ok\u0007\nline  ",
                JSONArray().put(conflict("44", "cs", "eth", "cs")).put(conflict(300, "cs", "eth", "cs"))
                    .put(conflict(5, "c s", "eth", "cs")).put("x").put(conflict(6, "cs", "nrf24", "cs"))))
            .put("sd", ev("probed", "present", "x".repeat(500))))!!
        assertEquals(setOf("fm", "sd"), e.modules.keys)
        val fm = e["fm"]!!
        assertNull(fm.probe)                       // unknown probe value ignored
        assertEquals("okline", fm.detail)          // control characters removed, trimmed
        assertEquals(listOf(PinConflict(6, "cs", "nrf24", "cs")), fm.conflicts)
        assertEquals(96, e["sd"]!!.detail!!.length)
        assertFalse(e.provedAbsent("cc1101"))
        // A dropped entry with probe:"absent" must not disable the capability.
        val info = DeviceInfo.fromCaps(body(JSONObject().put("cc1101", JSONObject().put("probe", "absent"))))
        assertTrue(info.caps.cc1101)
        assertNull(info.evidence)
    }

    @Test fun statusTextNeverClaimsAPhysicalTest() {
        fun s(level: EvidenceLevel, probe: ProbeResult? = null) = CapabilityEvidence.statusText(ModuleEvidence(level, probe))
        assertEquals("Not built for this board", s(EvidenceLevel.NONE))
        assertEquals("Compiled in", s(EvidenceLevel.COMPILED))
        assertEquals("Configured, not probed", s(EvidenceLevel.CONFIGURED))
        assertEquals("Probed: not detected", s(EvidenceLevel.CONFIGURED, ProbeResult.ABSENT))
        assertEquals("Not probed (bus busy)", s(EvidenceLevel.CONFIGURED, ProbeResult.BUSY))
        assertEquals("Detected by probe", s(EvidenceLevel.PROBED, ProbeResult.PRESENT))
        assertEquals("Driver initialized", s(EvidenceLevel.INITIALIZED, ProbeResult.PRESENT))
        for (level in EvidenceLevel.entries) for (probe in listOf(null) + ProbeResult.entries)
            assertFalse(s(level, probe).contains("test", ignoreCase = true))
    }

    @Test fun rowsFollowDisplayOrderThenUnknownModules() {
        val e = CapabilityEvidence.fromJson(tEmbedEvidence().put("zz_extra", ev("compiled")))!!
        val rows = e.rows()
        assertEquals(
            listOf("CC1101 Sub-GHz", "NFC reader", "NRF24L01", "Si4713 FM", "LoRa", "W5500 Ethernet",
                "Microphone", "iButton", "SD card", "Wi-Fi", "zz_extra"),
            rows.map { it.label },
        )
        val nrf = rows[2]
        assertEquals("Probed: not detected", nrf.status)
        assertEquals("SPI SETUP_AW readback invalid", nrf.detail)
        assertEquals(listOf("GPIO44 cs shared with eth cs", "GPIO43 ce shared with eth int"), nrf.conflicts)
    }

    @Test fun unavailableReasonExplainsFromEvidence() {
        val e = CapabilityEvidence.fromJson(tEmbedEvidence()
            .put("eth", ev("configured", "busy"))
            .put("speaker", ev("configured", detail = "board profile I2S pins; no chip identification")))!!
        assertEquals(
            "Checked when companion mode started: not detected (SPI SETUP_AW readback invalid)." +
                " Pin conflict: GPIO44 cs shared with eth cs; GPIO43 ce shared with eth int.",
            CapabilityEvidence.unavailableReason("nrf24", e),
        )
        assertEquals("Checked when companion mode started: not detected (no I2C ACK at 0x63).",
            CapabilityEvidence.unavailableReason("fm", e))
        assertEquals("Not probed: the bus was busy. Re-enter companion mode on the device to retry.",
            CapabilityEvidence.unavailableReason("eth", e))
        assertEquals("This firmware build has no support for it on this board.",
            CapabilityEvidence.unavailableReason("lora", e))
        assertEquals("Supported by this firmware, but no pins are configured on the device.",
            CapabilityEvidence.unavailableReason("ibutton", e))
        assertEquals("Pins are configured, but presence is not confirmed (board profile I2S pins; no chip identification).",
            CapabilityEvidence.unavailableReason("speaker", e))
        assertNull(CapabilityEvidence.unavailableReason("cc1101", e)) // present: keep generic message
        assertNull(CapabilityEvidence.unavailableReason("lf_rfid", e)) // not reported
        assertNull(CapabilityEvidence.unavailableReason(null, e))

        assertEquals("probed, not detected", CapabilityEvidence.shortReason("nrf24", e))
        assertEquals("not probed (bus busy)", CapabilityEvidence.shortReason("eth", e))
        assertEquals("not built for this board", CapabilityEvidence.shortReason("lora", e))
        assertEquals("pins not configured", CapabilityEvidence.shortReason("ibutton", e))
        assertEquals("configured, not confirmed", CapabilityEvidence.shortReason("speaker", e))
        assertNull(CapabilityEvidence.shortReason("cc1101", e))
        assertNull(CapabilityEvidence.shortReason("nfc", null))
    }
}
