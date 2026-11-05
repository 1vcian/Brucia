package app.brucia

import app.brucia.files.FileEntry
import app.brucia.session.CommandRunner
import app.brucia.wifi.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CaptureWorkflowTest {
    private val mac = "001122334455"
    private val address = "00:11:22:33:44:55"
    private val name = "HS_${mac}_fixture.pcap"
    private fun entry(n: String = name) = FileEntry(n, false, 3)
    private fun target() = Handshake("fixture", byteArrayOf(0,17,34,51,68,85), ByteArray(6),
        ByteArray(32), ByteArray(32), ByteArray(99), ByteArray(16), 2)
    private fun crc32(b: ByteArray) = CRC32().apply { update(b) }.value
    private class Link : CaptureLink {
        var before = emptyList<FileEntry>()
        var after = emptyList<FileEntry>()
        var captured = false
        var failure: String? = null
        var listings = 0
        var listError: String? = null
        val sent = mutableListOf<Pair<String, Long>>()
        val reads = mutableListOf<String>()
        var read: suspend (String) -> ByteArray = { if (captured) byteArrayOf(2) else byteArrayOf(1) }
        // Descriptor path (features.capture_descriptor): the device returns the file it wrote.
        var descriptor = false
        var captureOk = true
        var captureData: JSONObject? = null
        override val descriptorSupported get() = descriptor
        override suspend fun capture(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?> {
            sent.add(cmd to timeoutMs)
            return captureOk to captureData
        }
        override val commands = CommandRunner { cmd, timeout ->
            sent.add(cmd to timeout)
            if (failure != null) false to failure!! else {
                if (!cmd.startsWith("wifi_deauth")) captured = true
                true to "ok"
            }
        }
        override suspend fun list(): List<FileEntry> { listings++; listError?.let { throw java.io.IOException(it) }; return if (captured) after else before }
        override suspend fun read(path: String): ByteArray { reads.add(path); return read.invoke(path) }
    }
    private fun desc(path: String, size: Int, crc: Long, complete: Boolean = true, stop: String = "complete",
                     v: Int = 1, extra: JSONObject.() -> Unit = {}): JSONObject {
        val d = JSONObject().put("v", v).put("path", path).put("size", size).put("crc32", crc)
            .put("complete", complete).put("stop", stop).put("time_src", "device_uptime_ms").apply(extra)
        return JSONObject().put("desc", d)
    }
    private fun service(scope: CoroutineScope, link: Link?, loaded: MutableList<CapturedTarget>) =
        CaptureWorkflow(scope, { link }, { loaded.add(it) }, parse = { _, _ -> target() to null },
            compute = scope.virtualUnconfined)
    private suspend fun finished(service: CaptureWorkflow) {
        withTimeout(2000) { while (service.status.value.active) delay(5) }
    }

    @Test(timeout = 5000) fun importsOnlyChangedMatchingFileIncludingSameSizeOverwrite() = runVirtual {
        val link = Link().apply { before = listOf(entry()); after = before }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("ready", s.status.value.step)
        assertEquals(1, loaded.size)
        assertEquals(listOf("wifi_deauth $address 6 5", "wifi_handshake $address 6 20 fixture"), link.sent.map { it.first })
        assertEquals(listOf(15_000L, 40_000L), link.sent.map { it.second })
    }
    @Test(timeout = 5000) fun rejectsUnchangedOldFileAndOtherBssid() = runVirtual {
        for (foreign in listOf(false, true)) {
            val link = Link().apply {
                before = listOf(entry()); after = if (foreign) listOf(entry("HS_FFFFFFFFFFFF_fixture.pcap")) else before
                read = { byteArrayOf(1) }
            }
            val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
            s.start(address, 6, "fixture", 20, false); finished(s)
            assertEquals("failed", s.status.value.step); assertTrue(loaded.isEmpty())
            assertTrue(link.reads.none { "FFFFFFFFFFFF" in it })
        }
    }
    @Test(timeout = 5000) fun commandFailureStopsBeforeNextCommandOrPull() = runVirtual {
        val link = Link().apply { failure = "device error"; after = listOf(entry()) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("device error", s.status.value.message)
        assertEquals(1, link.sent.size); assertEquals(1, link.listings); assertTrue(link.reads.isEmpty())
    }
    @Test(timeout = 5000) fun rejectsMultipleChangedMatchesInsteadOfSortingNames() = runVirtual {
        val link = Link().apply { after = listOf(entry(), entry("HS_${mac}_zzz.pcap")) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertTrue(s.status.value.message.contains("Multiple changed")); assertTrue(loaded.isEmpty())
    }
    @Test(timeout = 5000) fun pmkidUsesOnlyItsOwnFileAndNoExtraDeauthCommand() = runVirtual {
        val link = Link().apply { after = listOf(entry(), entry("PMKID_$mac.pcap")) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 15, true); finished(s)
        assertEquals(listOf("wifi_pmkid $address 6 15"), link.sent.map { it.first })
        assertEquals("${CaptureWorkflow.DIRECTORY}/PMKID_$mac.pcap", loaded.single().path)
    }
    @Test(timeout = 5000) fun stoppedOrSupersededReadCannotImportOrOverwriteNewState() = runVirtual {
        for (restart in listOf(false, true)) {
            var gate: Continuation<ByteArray>? = null
            val link = Link().apply { after = listOf(entry()); read = { suspendCoroutine { gate = it } } }
            val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
            s.start(address, 6, "fixture", 20, false)
            withTimeout(2000) { while (gate == null) delay(5) }
            val old = gate!!
            s.stop("Disconnected")
            if (restart) {
                link.captured = false; link.read = { byteArrayOf(3) }
                s.start(address, 6, "fixture", 20, false); finished(s)
            }
            val state = s.status.value
            old.resume(byteArrayOf(2))
            withTimeout(2000) { coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(state, s.status.value)
            assertEquals(if (restart) 1 else 0, loaded.size)
        }
    }
    @Test(timeout = 5000) fun rejectsCaptureContentForOtherApEvenWithMatchingFilename() = runVirtual {
        val link = Link().apply { after = listOf(entry()) }
        val s = CaptureWorkflow(this, { link }, { fail("Wrong target imported") }, parse = { _, _ -> target().copy(apMac = ByteArray(6)) to null },
            compute = virtualUnconfined)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("failed", s.status.value.step)
    }
    @Test(timeout = 5000) fun failedPmkidCaptureNeverListsOrReadsItsOldOutput() = runVirtual {
        val link = Link().apply { failure = "capture failed"; after = listOf(entry("PMKID_$mac.pcap")) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 15, true); finished(s)
        assertEquals("capture failed", s.status.value.message)
        assertEquals(1, link.listings); assertTrue(link.reads.isEmpty()); assertTrue(loaded.isEmpty())
    }
    @Test(timeout = 5000) fun failedVerifiedReadNeverImports() = runVirtual {
        val link = Link().apply { after = listOf(entry()); read = { throw java.io.IOException("CRC mismatch") } }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("CRC mismatch", s.status.value.message); assertTrue(loaded.isEmpty())
    }

    @Test(timeout = 5000) fun failedBaselineListingSendsNoCaptureCommands() = runVirtual {
        val link = Link().apply { listError = "Storage unavailable" }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("Storage unavailable", s.status.value.message)
        assertTrue(link.sent.isEmpty()); assertTrue(loaded.isEmpty())
    }

    @Test(timeout = 5000) fun missingLinkAndInvalidParametersDoNotSend() = runVirtual {
        val loaded = mutableListOf<CapturedTarget>(); val link = Link()
        val s = service(this, link, loaded)
        s.start("bad", 6, "fixture", 20, false)
        assertFalse(s.status.value.active); assertTrue(link.sent.isEmpty())
        val offline = service(this, null, loaded)
        offline.start(address, 6, "fixture", 20, false)
        assertEquals("failed", offline.status.value.step)
        val blocked = CaptureWorkflow(this, { link }, { fail("Unexpected import") }, blockedReason = { "Update firmware" },
            compute = virtualUnconfined)
        blocked.start(address, 6, "fixture", 20, false)
        assertEquals("Update firmware", blocked.status.value.message)
        assertTrue(link.sent.isEmpty())
    }

    // --- features.capture_descriptor path ------------------------------------------------------

    @Test(timeout = 5000) fun descriptorImportsExactPathAndVerifiesWithoutListingDiff() = runVirtual {
        val path = "${CaptureWorkflow.DIRECTORY}/$name"
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val link = Link().apply {
            descriptor = true; captureData = desc(path, bytes.size, crc32(bytes)) { put("frames", 4).put("handshake", true) }
            read = { p -> if (p == path) bytes else throw java.io.IOException("unexpected read $p") }
        }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("ready", s.status.value.step)
        assertTrue(s.status.value.message.contains("device descriptor"))
        assertEquals(path, loaded.single().path)
        assertEquals(0, link.listings)                         // no listing diff on the descriptor path
        assertEquals(listOf(path), link.reads)                 // imports exactly the described file
        assertEquals("wifi_handshake $address 6 20 fixture", link.sent.last().first)
    }

    @Test(timeout = 5000) fun descriptorImportsAppendedFileVerifyingTheWholeAccumulatedFile() = runVirtual {
        // The file pre-existed and this run appended to it: size/crc cover the whole file, which the app
        // reads and verifies in full. A matching whole-file fingerprint still imports.
        val path = "${CaptureWorkflow.DIRECTORY}/$name"
        val whole = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val link = Link().apply {
            descriptor = true
            captureData = desc(path, whole.size, crc32(whole)) { put("appended", true).put("prior_size", 4).put("frames", 5) }
            read = { p -> if (p == path) whole else throw java.io.IOException("unexpected read $p") }
        }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("ready", s.status.value.step)
        assertEquals(path, loaded.single().path)
        assertTrue(whole.contentEquals(loaded.single().bytes))
    }

    @Test(timeout = 5000) fun descriptorRejectsSizeOrCrcMismatchAndNeverImports() = runVirtual {
        for (bad in listOf("size", "crc")) {
            val path = "${CaptureWorkflow.DIRECTORY}/$name"
            val bytes = byteArrayOf(9, 9, 9)
            val link = Link().apply {
                descriptor = true
                captureData = if (bad == "size") desc(path, bytes.size + 1, crc32(bytes))
                    else desc(path, bytes.size, crc32(bytes) xor 0xFFFF)
                read = { bytes }
            }
            val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
            s.start(address, 6, "fixture", 20, false); finished(s)
            assertEquals("failed", s.status.value.step)
            assertTrue(s.status.value.message.contains("verification failed"))
            assertTrue(loaded.isEmpty())
        }
    }

    @Test(timeout = 5000) fun descriptorStoppedOrPartialCaptureFailsWithReasonAndNoRead() = runVirtual {
        // Includes not_written: the device detected a handshake but wrote no new file (stale file only).
        for ((ok, complete, stop) in listOf(Triple(false, false, "timeout"), Triple(true, false, "aborted"),
                                            Triple(true, false, "not_written"))) {
            val link = Link().apply {
                descriptor = true; captureOk = ok
                captureData = desc("${CaptureWorkflow.DIRECTORY}/$name", 0, 0, complete = complete, stop = stop)
                read = { throw java.io.IOException("should not read a partial capture") }
            }
            val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
            s.start(address, 6, "fixture", 20, false); finished(s)
            assertEquals("failed", s.status.value.step)
            assertTrue(link.reads.isEmpty()); assertTrue(loaded.isEmpty())
        }
    }

    @Test(timeout = 5000) fun descriptorMissingOrWrongVersionFailsClosedNoListingFallback() = runVirtual {
        for (data in listOf(null, JSONObject().put("ok", true), desc("${CaptureWorkflow.DIRECTORY}/$name", 1, 0, v = 2))) {
            val link = Link().apply { descriptor = true; captureData = data; read = { byteArrayOf(1) } }
            val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
            s.start(address, 6, "fixture", 20, false); finished(s)
            assertEquals("failed", s.status.value.step)
            assertTrue(s.status.value.message.contains("valid capture descriptor"))
            assertEquals(0, link.listings); assertTrue(link.reads.isEmpty()); assertTrue(loaded.isEmpty())
        }
    }

    @Test(timeout = 5000) fun descriptorRejectsPathForOtherTargetOrTraversal() = runVirtual {
        for (path in listOf("${CaptureWorkflow.DIRECTORY}/HS_FFFFFFFFFFFF_x.pcap",
                            "${CaptureWorkflow.DIRECTORY}/PMKID_$mac.pcap",       // wrong kind for handshake
                            "${CaptureWorkflow.DIRECTORY}/notes.txt")) {
            val link = Link().apply {
                descriptor = true; captureData = desc(path, 1, crc32(byteArrayOf(1)))
                read = { throw java.io.IOException("should not read a mismatched path") }
            }
            val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
            s.start(address, 6, "fixture", 20, false); finished(s)
            assertEquals("failed", s.status.value.step)
            assertTrue(link.reads.isEmpty()); assertTrue(loaded.isEmpty())
        }
    }

    @Test(timeout = 5000) fun descriptorRejectsVerifiedBytesForOtherAp() = runVirtual {
        val path = "${CaptureWorkflow.DIRECTORY}/$name"
        val bytes = byteArrayOf(4, 5, 6)
        val link = Link().apply { descriptor = true; captureData = desc(path, bytes.size, crc32(bytes)); read = { bytes } }
        val loaded = mutableListOf<CapturedTarget>()
        val s = CaptureWorkflow(this, { link }, { loaded.add(it) }, parse = { _, _ -> target().copy(apMac = ByteArray(6)) to null },
            compute = virtualUnconfined)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("failed", s.status.value.step); assertTrue(loaded.isEmpty())
    }

    @Test(timeout = 5000) fun descriptorPmkidImportsOwnFileWithNoDeauth() = runVirtual {
        val path = "${CaptureWorkflow.DIRECTORY}/PMKID_$mac.pcap"
        val bytes = byteArrayOf(7, 7)
        val link = Link().apply {
            descriptor = true; captureData = desc(path, bytes.size, crc32(bytes)) { put("frames", 3) }
            read = { bytes }
        }
        val loaded = mutableListOf<CapturedTarget>()
        val s = CaptureWorkflow(this, { link }, { loaded.add(it) }, parse = { _, _ -> null to PmkidTarget(ByteArray(16), byteArrayOf(0,17,34,51,68,85), ByteArray(6), "fixture") },
            compute = virtualUnconfined)
        s.start(address, 6, "fixture", 15, true); finished(s)
        assertEquals("ready", s.status.value.step)
        assertEquals(path, loaded.single().path)
        assertEquals(listOf("wifi_pmkid $address 6 15"), link.sent.map { it.first }) // no deauth for pmkid
    }
}
