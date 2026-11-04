package app.brucia

import app.brucia.files.*
import app.brucia.session.CommandRunner
import app.brucia.wifi.EvilPortalService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FileTransferServiceTest {
    private class Link : FileTransferLink {
        var current = true
        val reads = mutableListOf<String>()
        var writes = 0
        var readBody: suspend () -> ByteArray = { byteArrayOf(1) }
        var writeBody: suspend () -> Unit = {}
        override fun isCurrent() = current
        override suspend fun read(path: String): ByteArray { reads += path; return readBody() }
        override suspend fun write(path: String, bytes: ByteArray) { writes++; writeBody() }
    }
    private fun CoroutineScope.service(link: Link) = FileTransferService(this, { link }, Dispatchers.Unconfined)

    @Test(timeout = 5000) fun snapshotAtInvocationPreventsQueuedReadFromFollowingReconnect() = runBlocking {
        val old = Link(); val fresh = Link()
        var current = old
        val service = FileTransferService(this, { current }, Dispatchers.Unconfined)
        // Capture the old connection before the coroutine is scheduled, then reconnect.
        var callbacks = 0
        val queued = service.submit(work = { read("old") }) { callbacks++ }
        old.current = false; current = fresh
        queued.join()
        assertTrue(queued.isCancelled)
        assertTrue(old.reads.isEmpty()); assertTrue(fresh.reads.isEmpty())
        assertEquals(0, callbacks)
        service.submit(work = { read("fresh") }) { assertTrue(it.isSuccess); callbacks++ }.join()
        assertEquals(listOf("fresh"), fresh.reads)
        assertEquals(1, callbacks)
    }

    @Test(timeout = 5000) fun stopCancelsActiveAndQueuedTransfersAndAllowsNextRequest() = runBlocking {
        val link = Link().apply { readBody = { awaitCancellation() } }
        val service = service(link)
        var callbacks = 0
        val first = service.submit(work = { read("first") }) { callbacks++ }
        until { link.reads.size == 1 }
        val second = service.submit(work = { read("queued") }) { callbacks++ }
        yield()
        service.cancelAll(); joinAll(first, second)
        assertTrue(first.isCancelled); assertTrue(second.isCancelled)
        assertEquals(listOf("first"), link.reads); assertEquals(0, callbacks)
        link.readBody = { byteArrayOf(2) }
        service.submit(work = { read("next") }) { callbacks++ }.join()
        assertEquals(listOf("first", "next"), link.reads); assertEquals(1, callbacks)
    }

    @Test(timeout = 5000) fun cancelledNonCancellableReadCannotOpenDestinationOrCallback() = runBlocking {
        var reply: Continuation<ByteArray>? = null
        val link = Link().apply { readBody = { suspendCoroutine { reply = it } } }
        val service = service(link)
        var opens = 0; var callbacks = 0
        val transfer = service.submit(work = { read("fixture"); checkActive(); opens++ }) { callbacks++ }
        until { reply != null }; service.cancelAll()
        reply!!.resume(byteArrayOf(1)); transfer.join()
        assertEquals(0, opens); assertEquals(0, callbacks)
    }

    @Test(timeout = 5000) fun supersededBlockingWriteFinishesBeforeNewDestinationWrite() = runBlocking {
        val service = service(Link())
        val events = mutableListOf<String>()
        var oldWrite: Continuation<Unit>? = null
        val old = service.submit("destination", work = {
            events += "old open"
            try { suspendCoroutine<Unit> { oldWrite = it }; events += "old write" }
            finally { events += "old close" }
            "old"
        }) { events += "old callback" }
        until { oldWrite != null }
        val fresh = service.submit("destination", work = { events += "new write"; "new" }) { events += "new callback" }
        yield()
        assertEquals(listOf("old open"), events)
        oldWrite!!.resume(Unit); joinAll(old, fresh)
        assertEquals(listOf("old open", "old write", "old close", "new write", "new callback"), events)
    }

    @Test(timeout = 5000) fun stalePreparedFileIsDiscardedWithoutChangingAcceptedFile() = runBlocking {
        val link = Link(); val service = service(link)
        val existing = File.createTempFile("brucia-kept-", ".bin").apply { writeText("keep") }
        var staged: File? = null
        var callbacks = 0
        try {
            val job = service.submit(onDiscard = { file: File -> file.delete() }, work = {
                File.createTempFile("brucia-stage-", ".bin").also {
                    staged = it; it.writeText("new"); link.current = false
                }
            }) { callbacks++ }
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(staged!!.exists()); assertEquals("keep", existing.readText())
            assertEquals(0, callbacks)
        } finally { existing.delete(); staged?.delete() }
    }

    @Test(timeout = 5000) fun verifiedReadFailureNeverOpensDestination() = runBlocking {
        val link = Link().apply { readBody = { throw IOException("checksum mismatch") } }
        val service = service(link)
        var opens = 0; var error = ""
        service.submit(work = { read("fixture"); opens++ }) { error = it.exceptionOrNull()?.message.orEmpty() }.join()
        assertEquals(0, opens); assertEquals("checksum mismatch", error)
    }

    @Test(timeout = 5000) fun unavailableVerifiedFilesReportsFailureWithoutIO() = runBlocking {
        var entered = false; var error = ""
        val service = FileTransferService(this, { throw IOException("verified files unavailable") }, Dispatchers.Unconfined)
        service.submit(work = { entered = true }) { error = it.exceptionOrNull()?.message.orEmpty() }.join()
        assertFalse(entered); assertEquals("verified files unavailable", error)
    }

    @Test(timeout = 5000) fun callerCancellationStopsActualUploadJob() = runBlocking {
        var closed = false
        val link = Link().apply { writeBody = { try { awaitCancellation() } finally { closed = true } } }
        val service = service(link)
        val caller = launch { service.await { write("fixture", byteArrayOf(1)) } }
        until { link.writes == 1 }; caller.cancelAndJoin()
        until { closed }
        assertTrue(caller.isCancelled)
    }

    @Test(timeout = 5000) fun stopAllOrChangedConnectionAlsoEndsAwaitingCaller() = runBlocking {
        for (changed in listOf(false, true)) {
            val link = Link()
            link.readBody = if (changed) ({ link.current = false; byteArrayOf(1) }) else ({ awaitCancellation() })
            val service = service(link)
            val caller = launch { service.await { read("fixture") }; fail("Cancelled transfer returned") }
            until { link.reads.isNotEmpty() }
            if (!changed) service.cancelAll()
            caller.join()
            assertTrue(caller.isCancelled)
        }
    }

    @Test(timeout = 5000) fun alreadyCancelledParentDoesNotRunOrPublish() = runBlocking {
        val parent = Job().apply { cancel() }
        val link = Link(); var callbacks = 0
        val service = FileTransferService(CoroutineScope(coroutineContext + parent), { link }, Dispatchers.Unconfined)
        val job = service.submit(work = { read("fixture") }) { callbacks++ }
        job.join()
        assertTrue(job.isCancelled); assertTrue(link.reads.isEmpty()); assertEquals(0, callbacks)
    }

    @Test(timeout = 5000) fun portalStopAndTransferCancellationCannotLaunchPortalAfterUpload() = runBlocking {
        for (portalStop in listOf(true, false)) {
            var closed = false
            val link = Link().apply { writeBody = { try { awaitCancellation() } finally { closed = true } } }
            val service = service(link)
            val commands = mutableListOf<String>()
            val portal = EvilPortalService(this, CommandRunner { cmd, _ -> commands += cmd; true to "stopped" },
                { true }, { path, bytes -> service.await { write(path, bytes) }; true to "uploaded" }, {})
            portal.start("fixture", byteArrayOf(1), "fixture.html")
            until { link.writes == 1 }
            if (portalStop) portal.stop() else service.cancelAll()
            coroutineContext[Job]!!.children.toList().joinAll()
            assertTrue(closed); assertFalse(portal.active.value)
            assertTrue(commands.none { it.startsWith("evil_portal_start") })
            if (portalStop) assertEquals(listOf("evil_portal_stop"), commands)
            else assertEquals("Template upload cancelled.", portal.status.value)
        }
    }
}
