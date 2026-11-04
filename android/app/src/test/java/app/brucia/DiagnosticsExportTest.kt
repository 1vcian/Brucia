package app.brucia

import app.brucia.protocol.LinkCounters
import app.brucia.report.ReportData
import app.brucia.report.SessionReport
import app.brucia.wardrive.DeviceDiagState
import app.brucia.wardrive.DeviceDiagnostics
import app.brucia.wardrive.Diagnostics
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Diagnostics section of the local log export header and of the session report. */
class DiagnosticsExportTest {

    private fun ready(): DeviceDiagState {
        val err = JSONObject()
        listOf("400", "404", "409", "413", "4xx", "5xx", "other").forEach { err.put(it, 0) }
        err.put("409", 3)
        val link = JSONObject()
        DeviceDiagnostics.LINK_KEYS.forEach { link.put(it, 0) }
        link.put("rx_bad_json", 5).put("err", err)
        val body = JSONObject().put("ok", true).put("v", 1).put("reset", "panic").put("reset_code", 4)
            .put("uptime_s", 61).put("heap", JSONObject().put("free", 1000).put("min_free", 900).put("largest", 800))
            .put("link", link).put("drops", JSONObject())
        return DeviceDiagState.Ready(DeviceDiagnostics.fromAck(body)!!, "10:11:12")
    }

    @Test fun logExportHeaderCarriesAppAndDeviceDiagnostics() {
        val counters = LinkCounters()
        counters.noteMalformedFrame()
        val lines = Diagnostics.exportLines(counters.snapshot(), ready())
        val header = BruciaLog.exportHeader("9.9.9", 1L, "Firmware: x", lines)
        assertTrue(header.contains("\nDiagnostics:\n"))
        assertTrue(header.contains("  App skipped frames: 1 malformed, 0 invalid length (link reset)\n"))
        assertTrue(header.contains("  Device reset reason: panic (code 4), uptime 00:01:01\n"))
        assertTrue(header.contains("  Device ERR replies: 409 x3"))
        assertTrue(header.endsWith("----\n"))
        assertFalse(BruciaLog.exportHeader("9.9.9", 1L, null, emptyList()).contains("Diagnostics:"))
    }

    @Test fun sessionReportHasDiagnosticsSectionOnlyWhenProvided() {
        val lines = Diagnostics.exportLines(LinkCounters().snapshot(), DeviceDiagState.Unsupported)
        val md = SessionReport.markdown(ReportData(diagnostics = lines)) { null }
        assertTrue(md.contains("\n## Diagnostics\n\n- App link since app start: 0 link(s)"))
        assertTrue(md.contains("- Device diagnostics: not supported by this firmware\n"))
        assertTrue(md.indexOf("## Diagnostics") > md.indexOf("## Insights"))
        assertFalse(SessionReport.markdown(ReportData()) { null }.contains("## Diagnostics"))
        val full = SessionReport.markdown(ReportData(diagnostics = Diagnostics.exportLines(LinkCounters().snapshot(), ready()))) { null }
        assertTrue(full.contains("- Device diagnostics fetched at 10:11:12 (counters since device boot)"))
        assertTrue(full.contains("- Device rejected frames: 5 bad JSON"))
    }
}
