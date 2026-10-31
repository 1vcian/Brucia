package app.brucia

import app.brucia.files.FileEntry
import app.brucia.session.CommandRunner
import app.brucia.wifi.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
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
    private fun service(scope: CoroutineScope, link: Link?, loaded: MutableList<CapturedTarget>) =
        CaptureWorkflow(scope, { link }, { loaded.add(it) }, parse = { _, _ -> target() to null })
    private suspend fun finished(service: CaptureWorkflow) {
        withTimeout(2000) { while (service.status.value.active) delay(5) }
    }

    @Test(timeout = 5000) fun importsOnlyChangedMatchingFileIncludingSameSizeOverwrite() = runBlocking {
        val link = Link().apply { before = listOf(entry()); after = before }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("ready", s.status.value.step)
        assertEquals(1, loaded.size)
        assertEquals(listOf("wifi_deauth $address 6 5", "wifi_handshake $address 6 20 fixture"), link.sent.map { it.first })
        assertEquals(listOf(15_000L, 40_000L), link.sent.map { it.second })
    }
    @Test(timeout = 5000) fun rejectsUnchangedOldFileAndOtherBssid() = runBlocking {
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
    @Test(timeout = 5000) fun commandFailureStopsBeforeNextCommandOrPull() = runBlocking {
        val link = Link().apply { failure = "device error"; after = listOf(entry()) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("device error", s.status.value.message)
        assertEquals(1, link.sent.size); assertEquals(1, link.listings); assertTrue(link.reads.isEmpty())
    }
    @Test(timeout = 5000) fun rejectsMultipleChangedMatchesInsteadOfSortingNames() = runBlocking {
        val link = Link().apply { after = listOf(entry(), entry("HS_${mac}_zzz.pcap")) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertTrue(s.status.value.message.contains("Multiple changed")); assertTrue(loaded.isEmpty())
    }
    @Test(timeout = 5000) fun pmkidUsesOnlyItsOwnFileAndNoExtraDeauthCommand() = runBlocking {
        val link = Link().apply { after = listOf(entry(), entry("PMKID_$mac.pcap")) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 15, true); finished(s)
        assertEquals(listOf("wifi_pmkid $address 6 15"), link.sent.map { it.first })
        assertEquals("${CaptureWorkflow.DIRECTORY}/PMKID_$mac.pcap", loaded.single().path)
    }
    @Test(timeout = 5000) fun stoppedOrSupersededReadCannotImportOrOverwriteNewState() = runBlocking {
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
    @Test(timeout = 5000) fun rejectsCaptureContentForOtherApEvenWithMatchingFilename() = runBlocking {
        val link = Link().apply { after = listOf(entry()) }
        val s = CaptureWorkflow(this, { link }, { fail("Wrong target imported") }, parse = { _, _ -> target().copy(apMac = ByteArray(6)) to null })
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("failed", s.status.value.step)
    }
    @Test(timeout = 5000) fun failedPmkidCaptureNeverListsOrReadsItsOldOutput() = runBlocking {
        val link = Link().apply { failure = "capture failed"; after = listOf(entry("PMKID_$mac.pcap")) }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 15, true); finished(s)
        assertEquals("capture failed", s.status.value.message)
        assertEquals(1, link.listings); assertTrue(link.reads.isEmpty()); assertTrue(loaded.isEmpty())
    }
    @Test(timeout = 5000) fun failedVerifiedReadNeverImports() = runBlocking {
        val link = Link().apply { after = listOf(entry()); read = { throw java.io.IOException("CRC mismatch") } }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("CRC mismatch", s.status.value.message); assertTrue(loaded.isEmpty())
    }

    @Test(timeout = 5000) fun failedBaselineListingSendsNoCaptureCommands() = runBlocking {
        val link = Link().apply { listError = "Storage unavailable" }
        val loaded = mutableListOf<CapturedTarget>(); val s = service(this, link, loaded)
        s.start(address, 6, "fixture", 20, false); finished(s)
        assertEquals("Storage unavailable", s.status.value.message)
        assertTrue(link.sent.isEmpty()); assertTrue(loaded.isEmpty())
    }

    @Test(timeout = 5000) fun missingLinkAndInvalidParametersDoNotSend() = runBlocking {
        val loaded = mutableListOf<CapturedTarget>(); val link = Link()
        val s = service(this, link, loaded)
        s.start("bad", 6, "fixture", 20, false)
        assertFalse(s.status.value.active); assertTrue(link.sent.isEmpty())
        val offline = service(this, null, loaded)
        offline.start(address, 6, "fixture", 20, false)
        assertEquals("failed", offline.status.value.step)
        val blocked = CaptureWorkflow(this, { link }, { fail("Unexpected import") }, blockedReason = { "Update firmware" })
        blocked.start(address, 6, "fixture", 20, false)
        assertEquals("Update firmware", blocked.status.value.message)
        assertTrue(link.sent.isEmpty())
    }
}
