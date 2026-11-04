package app.brucia

import app.brucia.report.ReportData
import app.brucia.report.SessionReport
import app.brucia.wardrive.DeviceBuild
import app.brucia.wardrive.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsProvenanceTest {
    private fun legacyCaps() = JSONObject()
        .put("board", "Lilygo T-Embed CC1101").put("chip", "esp32-s3").put("fw", "0.1.2").put("proto", 0)
        .put("modules", JSONArray().put("wifi").put("rf")).put("battery", 87)
        .put("features", JSONObject().put("verified_files", true))
        .put("caps", JSONObject().put("cc1101", true))

    private fun build() = JSONObject()
        .put("commit", "aa98dfb7c0de").put("dirty", true).put("id", "0.1.3+lilygo-t-embed-cc1101.aa98dfb7c0de.dirty")
        .put("time", "2026-11-03T10:22:01Z").put("time_src", "commit").put("env", "lilygo-t-embed-cc1101")

    @Test fun firmwareWithoutBuildObjectStillParsesAndReportsNotReported() {
        val info = DeviceInfo.fromCaps(legacyCaps())
        assertNull(info.build)
        assertEquals(0, info.proto)
        assertEquals("0.1.2", info.fw)
        assertEquals(listOf("wifi", "rf"), info.modules)
        assertTrue(info.caps.cc1101)
        assertTrue(info.features.verifiedFiles)
        assertEquals(listOf("Build: not reported by this firmware"), info.buildLines())
        assertTrue(info.provenanceLines().first().startsWith("Firmware: 0.1.2 / protocol 0"))
    }

    @Test fun buildObjectIsParsedAndShownWithoutChangingExistingFields() {
        val info = DeviceInfo.fromCaps(legacyCaps().put("fw", "0.1.3").put("build", build()))
        val b = info.build!!
        assertEquals("aa98dfb7c0de", b.commit)
        assertEquals(true, b.dirty)
        assertEquals("0.1.3+lilygo-t-embed-cc1101.aa98dfb7c0de.dirty", b.id)
        assertEquals("2026-11-03T10:22:01Z", b.time)
        assertEquals("commit", b.timeSource)
        assertEquals("lilygo-t-embed-cc1101", b.env)
        assertEquals("0.1.3", info.fw)
        assertTrue(info.caps.cc1101)
        assertEquals(listOf(
            "Commit: aa98dfb7c0de (dirty)",
            "Build: 0.1.3+lilygo-t-embed-cc1101.aa98dfb7c0de.dirty / 2026-11-03T10:22:01Z (commit) / env lilygo-t-embed-cc1101",
        ), info.buildLines())
    }

    @Test fun unknownProvenanceFromSourceTarballIsKeptAsUnknownNotInvented() {
        val tarball = JSONObject().put("commit", "unknown").put("id", "0.1.3+e.unknown")
            .put("time", "unknown").put("time_src", "unknown").put("env", "e")
        val b = DeviceBuild.fromJson(tarball)!!
        assertNull(b.dirty) // omitted by firmware when Git state was unavailable
        assertEquals("unknown", b.commit)
        assertEquals("Commit: unknown", DeviceInfo("b", "c", "f", emptyList(), build = b).buildLines().first())
    }

    @Test fun malformedOrHostileBuildValuesAreDropped() {
        val bad = JSONObject().put("commit", 1234).put("dirty", "true").put("id", "a\nb")
            .put("time", "x".repeat(65)).put("time_src", JSONObject.NULL).put("env", "ok-env")
        val b = DeviceBuild.fromJson(bad)!!
        assertNull(b.commit)
        assertNull(b.dirty)
        assertNull(b.id)
        assertNull(b.time)
        assertNull(b.timeSource)
        assertEquals("ok-env", b.env)
        assertNull(DeviceBuild.fromJson(JSONObject().put("commit", "")))
        assertNull(DeviceBuild.fromJson(null))
        assertNull(DeviceInfo.fromCaps(legacyCaps().put("build", "abc")).build)
    }

    @Test fun missingOrMalformedProtoIsUnknown() {
        assertNull(DeviceInfo.fromCaps(legacyCaps().apply { remove("proto") }).proto)
        assertNull(DeviceInfo.fromCaps(legacyCaps().put("proto", "0")).proto)
        assertTrue(DeviceInfo.fromCaps(JSONObject()).provenanceLines().first().endsWith("protocol unknown"))
    }

    @Test fun logExportHeaderAndSessionReportNameTheArtifact() {
        val info = DeviceInfo.fromCaps(legacyCaps().put("fw", "0.1.3").put("build", build()))
        val provenance = info.provenanceLines().joinToString("; ")
        val header = BruciaLog.exportHeader("9.9.9", 1_000_123L, provenance)
        assertTrue(header.contains("App: 9.9.9 (1000123)"))
        assertTrue(header.contains("Commit: aa98dfb7c0de (dirty)"))
        assertTrue(BruciaLog.exportHeader("9.9.9", 1L, null).contains("Last device: none connected"))
        val md = SessionReport.markdown(ReportData(fw = info.fw, board = info.board, provenance = info.provenanceLines())) { null }
        assertTrue(md.contains("Commit: aa98dfb7c0de (dirty)"))
        assertTrue(md.contains("Firmware: 0.1.3 / protocol 0"))
        assertFalse(SessionReport.markdown(ReportData()) { null }.contains("Commit:"))
    }
}
