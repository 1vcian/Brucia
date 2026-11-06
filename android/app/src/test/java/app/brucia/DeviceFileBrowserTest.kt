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

    @Test(timeout = 5_000) fun listParsesEntriesAndDrivesLoading() = runVirtual {
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

    @Test(timeout = 5_000) fun changingDirectoryClearsStaleEntriesAndSupersedesTheOldListing() = runVirtual {
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

    @Test(timeout = 5_000) fun removeAndMkdirRelistTheLastDirectoryEvenOnFailure() = runVirtual {
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

    @Test(timeout = 5_000) fun stopEndsLoadingAndPreventsTheRelist() = runVirtual {
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

    @Test(timeout = 5_000) fun captureListingIsIndependentText() = runVirtual {
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

    @Test(timeout = 5_000) fun notConnectedSendsNothing() = runVirtual {
        connected = false
        val s = svc()
        s.list("/"); s.remove("/x"); s.mkdir("/y"); s.listCaptures()
        settle()
        assertTrue(runner.calls.isEmpty())
        assertFalse(s.loading.value)
        assertEquals("Connect the device over USB first.", s.captureList.value)
    }

    @Test(timeout = 5_000) fun pathsWithSpacesOrSeparatorsAreQuotedForSimpleCli() = runVirtual {
        val s = svc()
        // Unquoted, SimpleCLI split "/My Files" into two words (unknown argument: nothing listed)
        // and ";;" into a second CLI command.
        s.list("/My Files")
        answer(0, true, "a b.txt\t3\n")
        assertEquals(listOf(FileEntry("a b.txt", false, 3)), s.entries.value)
        s.remove("/My Files/a b.txt")
        answer(1, true, "File removed")
        assertEquals("File removed", s.changeStatus.value)
        answer(2, true, "")
        s.mkdir("/x;;storage remove /keep")
        answer(3, false, "Error creating directory")
        assertEquals("Error creating directory", s.changeStatus.value)
        answer(4, true, "")
        s.list("-dash")
        answer(5, true, "")
        assertEquals(
            listOf("storage list \"/My Files\"", "storage remove \"/My Files/a b.txt\"", "storage list \"/My Files\"",
                "storage mkdir \"/x;;storage remove /keep\"", "storage list \"/My Files\"", "storage list \"-dash\""),
            runner.cmds,
        )
        // Plain paths stay as before.
        assertEquals("storage list /BrucePCAP", DeviceFileBrowser.listCommand("/BrucePCAP"))
    }

    @Test(timeout = 5_000) fun pathsSimpleCliCannotCarryAreRefusedLocally() = runVirtual {
        val s = svc()
        s.list("/say \"hi\"")
        settle()
        assertTrue(s.listStatus.value.contains("cannot be sent"))
        s.remove("/a\\b"); s.mkdir("/tab\there"); s.remove("/trailing ")
        settle()
        assertTrue(s.changeStatus.value.contains("cannot be sent"))
        assertTrue(runner.calls.isEmpty())
        assertFalse(s.loading.value)
    }

    @Test(timeout = 5_000) fun aListingCutByTheReplyLimitSaysSoAndDropsThePartialLine() = runVirtual {
        val s = svc()
        s.list("/big")
        // 200 complete lines, then the capture limit cut "last.bin\t4096" to "last.bin\t40".
        val lines = (0 until 200).joinToString("") { "file_%03d.bin\t%d\n".format(it, it) }
        var out = lines + "last.bin\t4096\n"
        while (out.toByteArray().size < 3500) out += "pad_${out.length}.bin\t1\n"
        out = String(out.toByteArray().copyOf(3500))
        assertFalse(out.endsWith("\n"))
        answer(0, true, out)
        assertTrue(s.listStatus.value.startsWith("The device's reply limit (3500 bytes) cut this listing"))
        val partial = out.substring(out.lastIndexOf('\n') + 1).substringBefore('\t')
        assertTrue(s.entries.value.none { it.name == partial })
        assertTrue(s.entries.value.size >= 200)
        // A complete short listing clears the notice.
        s.list("/big")
        answer(1, true, ROOT)
        assertEquals("", s.listStatus.value)
    }

    private companion object {
        const val ROOT = "a.txt\t12\nBrucePCAP\t<DIR>\n"
    }
}
