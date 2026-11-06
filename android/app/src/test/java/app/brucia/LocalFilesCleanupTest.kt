package app.brucia

import app.brucia.report.ExportFiles
import app.brucia.report.ReportData
import app.brucia.report.SessionReport
import app.brucia.ble.BleDevice
import app.brucia.update.AppUpdater
import app.brucia.utils.FileRetention
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Local leftovers stay bounded: exports, pulled copies and update downloads (backlog DT06-DT08). */
class LocalFilesCleanupTest {
    private fun tempDir(): File = Files.createTempDirectory("brucia-cleanup-").toFile().also { it.deleteOnExit() }

    private fun File.touch(name: String, mtime: Long): File = File(this, name).also { it.writeText(name); it.setLastModified(mtime) }

    @Test fun pruneKeepsTheNewestMatchingFilesOnly() {
        val dir = tempDir()
        for (i in 1..25) dir.touch("pull-$i.bin", 1_000_000L + i * 1000)
        dir.touch("capture-x.pcap", 2_000_000L)
        dir.touch("other.txt", 1L)
        File(dir, "pull-dir").mkdir()
        assertEquals(6, FileRetention.prune(dir, listOf("pull-", "capture-"), 20))
        val left = dir.list()!!.toSet()
        assertTrue("capture-x.pcap" in left && "other.txt" in left && "pull-dir" in left)
        assertTrue((7..25).all { "pull-$it.bin" in left })
        assertTrue((1..6).none { "pull-$it.bin" in left })
        assertEquals(0, FileRetention.prune(File(dir, "missing"), listOf("pull-"), 1))
    }

    @Test fun exportsAreWrittenAndBoundedPerKind() {
        val dir = tempDir()
        for (i in 1..12) dir.touch("brucia_report_$i.md", 1_000L * i)
        dir.touch("brucia_session_1.json", 1L)
        val f = ExportFiles.write(dir, "brucia_report_", "md", "# report", now = 99)
        assertEquals("brucia_report_99.md", f.name)
        assertEquals("# report", f.readText())
        assertEquals(ExportFiles.KEEP, dir.list()!!.count { it.startsWith("brucia_report_") })
        assertTrue(f.exists() && File(dir, "brucia_session_1.json").exists())
    }

    @Test fun anExportFailureIsAnIOExceptionNotACrash() {
        val blocker = File(tempDir(), "not-a-dir").also { it.writeText("x") }
        try { ExportFiles.write(blocker, "brucia_report_", "md", "x"); fail() } catch (_: IOException) { }
    }

    /** F31: named exports (wardrive, NDEF, hc22000, RF, stations, probes, pcap) share the guarded contract. */
    @Test fun namedExportFailuresAreIOExceptionsAndLeaveNoPartialFile() {
        val blocker = File(tempDir(), "not-a-dir").also { it.writeText("x") }
        try { ExportFiles.writeNamed(blocker, "brucia_1.csv", "a,b"); fail() } catch (_: IOException) { } // unavailable dir / open fails
        val dir = tempDir()
        try {
            ExportFiles.writeNamed(dir, "brucia_monitor_1.pcap") { it.write(byteArrayOf(1, 2, 3)); throw IOException("ENOSPC") }
            fail()
        } catch (e: IOException) { assertEquals("ENOSPC", e.message) } // write fails part-way
        assertFalse(File(dir, "brucia_monitor_1.pcap").exists())
        try {
            ExportFiles.writeNamed(dir, "brucia_2.kml") { throw IllegalStateException("build failed") }
            fail()
        } catch (e: IOException) { assertEquals("build failed", e.message) } // non-IO failure still recoverable
        assertFalse(File(dir, "brucia_2.kml").exists())
        val ok = ExportFiles.writeNamed(File(dir, "sub"), "brucia_ndef.txt", "D1")
        assertEquals("D1", ok.readText())
    }

    @Test fun staleUpdateDownloadsAreRemovedFreshOnesKept() {
        val now = 10_000_000_000L
        val dir = tempDir()
        val old = now - AppUpdater.STALE_DOWNLOAD_MS - 1
        dir.touch(AppUpdater.APK_FILE, old)
        dir.touch("brucia-update-123.part", old)
        dir.touch("brucia-update-456.part", now - 1_000) // a download in progress
        dir.touch("unrelated.apk", old)
        val legacy = tempDir().also { it.touch(AppUpdater.APK_FILE, old) }
        assertEquals(3, AppUpdater.cleanupStaleDownloads(listOf(dir, legacy, File(dir, "missing")), now))
        assertEquals(setOf("brucia-update-456.part", "unrelated.apk"), dir.list()!!.toSet())
        assertTrue(legacy.list()!!.isEmpty())
        // An APK the installer may still be reading (just downloaded) stays.
        dir.touch(AppUpdater.APK_FILE, now - 60_000)
        assertEquals(0, AppUpdater.cleanupStaleDownloads(listOf(dir), now))
    }

    @Test fun deviceTextCannotBreakTheReportStructure() {
        val md = SessionReport.markdown(ReportData(ble = listOf(BleDevice("AA:BB:CC:DD:EE:FF", "x\n## Fake heading", -40, 0L))))
        assertFalse(md.contains("\n## Fake heading"))
        assertTrue(md.contains("\"x ## Fake heading\""))
    }
}
