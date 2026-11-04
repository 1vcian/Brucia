package app.brucia

import app.brucia.files.DeviceFileBrowser
import app.brucia.files.FileEntry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Reuses GateRunner / settle from DeviceOperationTest; single-threaded, no wall-clock waits. */
class DeviceFileBrowserTest {
    private val runner = GateRunner().apply { sticky = true }
    private var connected = true

    private fun CoroutineScope.svc() = DeviceFileBrowser(this, runner) { connected }

    private suspend fun answer(i: Int, ok: Boolean, out: String) {
        settle()
        assertTrue("call $i not sent", runner.calls.size > i)
        runner.reply(i, ok, out)
        settle()
    }

    @Test(timeout = 5_000) fun listParsesEntriesAndDrivesLoading() = runBlocking {
        val s = svc()
        s.list("/")
        assertTrue(s.loading.value)
        answer(0, true, ROOT)
        assertFalse(s.loading.value)
        assertEquals(listOf(FileEntry("BrucePCAP", true, 0), FileEntry("a.txt", false, 12)), s.entries.value)
        // Same directory: entries stay visible while refreshing.
        s.list("/")
        settle()
        assertEquals(2, s.entries.value.size)
        assertTrue(s.loading.value)
        answer(1, false, "No response from the device.")
        assertEquals(emptyList<FileEntry>(), s.entries.value)
        assertEquals(listOf("storage list /", "storage list /").map { it to 650_000L }, runner.calls.toList())
    }

    @Test(timeout = 5_000) fun changingDirectoryClearsStaleEntriesAndSupersedesTheOldListing() = runBlocking {
        val s = svc()
        s.list("/")
        answer(0, true, ROOT)
        s.list("/BrucePCAP")
        assertEquals(emptyList<FileEntry>(), s.entries.value)
        settle()
        s.list("/BrucePCAP/handshakes")
        settle()
        runner.reply(1, true, ROOT) // superseded reply for /BrucePCAP
        settle()
        assertEquals(emptyList<FileEntry>(), s.entries.value)
        assertTrue(s.loading.value)
        answer(2, true, "cap.pcap\t2048\n")
        assertEquals(listOf(FileEntry("cap.pcap", false, 2048)), s.entries.value)
    }

    @Test(timeout = 5_000) fun removeAndMkdirRelistTheLastDirectoryEvenOnFailure() = runBlocking {
        val s = svc()
        s.list("/d")
        answer(0, true, "x\t1\n")
        s.remove("/d/x")
        settle()
        assertFalse(s.loading.value) // a change alone does not show the listing as loading
        answer(1, true, "")
        assertTrue(s.loading.value)
        answer(2, true, "")
        assertEquals(emptyList<FileEntry>(), s.entries.value)
        s.mkdir("/d/new")
        answer(3, false, "mkdir failed")
        answer(4, true, "new\t<DIR>\n")
        assertEquals(listOf(FileEntry("new", true, 0)), s.entries.value)
        assertEquals(
            listOf("storage list /d", "storage remove /d/x", "storage list /d", "storage mkdir /d/new", "storage list /d"),
            runner.cmds,
        )
    }

    @Test(timeout = 5_000) fun stopEndsLoadingAndPreventsTheRelist() = runBlocking {
        val s = svc()
        s.list("/d")
        answer(0, true, "x\t1\n")
        s.list("/d")
        s.remove("/d/x")
        settle()
        s.stop()
        assertFalse(s.loading.value)
        runner.reply(1, true, "late\t1\n")
        runner.reply(2, true, "")
        settle()
        assertEquals(listOf(FileEntry("x", false, 1)), s.entries.value)
        assertEquals(3, runner.calls.size)
    }

    @Test(timeout = 5_000) fun captureListingIsIndependentText() = runBlocking {
        val s = svc()
        s.list("/d")
        s.listCaptures()
        settle()
        assertTrue(s.loading.value)
        answer(1, true, "hs_1.pcap\t900\n")
        assertEquals("hs_1.pcap\t900\n", s.captureList.value)
        assertTrue(s.loading.value) // the browser listing still owns its wait
        answer(0, true, "")
        s.listCaptures()
        answer(2, false, "No response from the device.")
        assertEquals("No response from the device.", s.captureList.value)
        assertEquals("storage list /BrucePCAP/handshakes", runner.cmds[1])
    }

    @Test(timeout = 5_000) fun notConnectedSendsNothing() = runBlocking {
        connected = false
        val s = svc()
        s.list("/"); s.remove("/x"); s.mkdir("/y"); s.listCaptures()
        settle()
        assertTrue(runner.calls.isEmpty())
        assertFalse(s.loading.value)
        assertEquals("Connect the device over USB first.", s.captureList.value)
    }

    private companion object {
        const val ROOT = "a.txt\t12\nBrucePCAP\t<DIR>\n"
    }
}
