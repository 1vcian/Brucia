package app.brucia

import app.brucia.audio.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PcmStreamServiceTest {
    private class Link : PcmStreamLink {
        var current = true
        val events = CopyOnWriteArrayList<String>()
        val counts = mutableListOf<Int>()
        var beginBody: suspend () -> Pair<Boolean, String> = { true to "ok" }
        var dataBody: suspend () -> PcmDataReply = { PcmDataReply(true, "ok") }
        var stopBody: suspend () -> Pair<Boolean, String> = { true to "ok" }
        override fun isCurrent() = current
        override suspend fun begin(): Pair<Boolean, String> { events += "begin"; return beginBody() }
        override suspend fun data(samples: ShortArray, count: Int): PcmDataReply {
            events += "data"; counts += count; return dataBody()
        }
        override suspend fun stop(): Pair<Boolean, String> { events += "stop"; return stopBody() }
    }
    private class Reader(size: Int = 1600) : PcmSource.PcmReader {
        private val reader = PcmSource.arrayReader(ShortArray(size) { 100 })
        var closes = 0
        var reads = 0
        override fun read(out: ShortArray): Int { reads++; return reader.read(out) }
        override fun close() { closes++ }
    }
    private fun CoroutineScope.service(link: Link) = PcmStreamService(this, { link }, Dispatchers.Unconfined, pause = {})
    private fun PcmStreamService.startReader(reader: PcmSource.PcmReader, spec: PcmStreamSpec = PcmStreamSpec()) =
        start(spec, "preparing", "playing") { PcmStreamSource(reader, 1600) }
    private suspend fun done(service: PcmStreamService) = until { !service.status.value.active }

    @Test(timeout = 5000) fun waitsForBeginThenFramesAndPacesAt16kBeforeStopping() = runBlocking {
        val link = Link(); val gate = CompletableDeferred<Pair<Boolean, String>>()
        link.beginBody = { gate.await() }
        var clock = 0L; val waits = mutableListOf<Long>()
        val reader = Reader()
        val service = PcmStreamService(this, { link }, Dispatchers.Unconfined, { clock }, { waits += it; clock += it })
        service.startReader(reader, PcmStreamSpec(fmKHz = 100000))
        until { link.events.size == 1 }
        assertEquals(0, reader.reads)
        gate.complete(true to "ok"); done(service)
        assertEquals(listOf("begin", "data", "data", "stop"), link.events)
        assertEquals(listOf(1024, 576), link.counts)
        assertEquals(listOf(64L, 36L), waits)
        assertEquals(100L, service.status.value.elapsedMs)
        assertEquals(1f, service.status.value.progress)
        // This fake answers like older firmware (no accepted count): FM delivery is labelled unknown.
        assertEquals(PcmDelivery.UNKNOWN, service.status.value.delivery)
        assertEquals("Finished; delivery unknown (firmware does not report accepted samples).", service.status.value.message)
        assertEquals(1, reader.closes)
    }

    @Test(timeout = 5000) fun failedBeginSendsNoDataClosesSourceAndKeepsError() = runBlocking {
        val link = Link().apply { beginBody = { false to "speaker init failed" } }
        val reader = Reader(); val service = service(link)
        service.startReader(reader); done(service)
        assertEquals(listOf("begin", "stop"), link.events)
        assertEquals(0, reader.reads); assertEquals(1, reader.closes)
        assertEquals("speaker init failed", service.status.value.message)
    }

    @Test(timeout = 5000) fun rejectedDataAndReadExceptionsAreNotReportedAsFinished() = runBlocking {
        for (readError in listOf(false, true)) {
            val link = Link().apply { dataBody = { PcmDataReply(false, "frame rejected") } }
            var closes = 0
            val reader = object : PcmSource.PcmReader {
                override fun read(out: ShortArray): Int { if (readError) throw IOException("source failed"); return out.size }
                override fun close() { closes++ }
            }
            val service = service(link); service.startReader(reader); done(service)
            assertEquals(if (readError) "source failed" else "frame rejected", service.status.value.message)
            assertEquals(1, closes); assertEquals("stop", link.events.last())
        }
    }

    @Test(timeout = 5000) fun stopDuringNonCancellableEncodeClosesLateSourceWithoutBeginning() = runBlocking {
        var encoded: Continuation<PcmStreamSource?>? = null
        val link = Link(); val reader = Reader(); val service = service(link)
        service.start(PcmStreamSpec(), "encoding", "playing") { suspendCoroutine { encoded = it } }
        until { encoded != null }
        assertTrue(service.status.value.active)
        service.stop(); val stopped = service.status.value
        encoded!!.resume(PcmStreamSource(reader))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertTrue(link.events.isEmpty()); assertEquals(1, reader.closes)
        assertEquals(stopped, service.status.value)
    }

    @Test(timeout = 5000) fun restartWaitsForOldStopAndCannotBeClearedByItsFinalizer() = runBlocking {
        val old = Link(); val fresh = Link(); var current = old
        val begin = CompletableDeferred<Pair<Boolean, String>>()
        val stop = CompletableDeferred<Pair<Boolean, String>>()
        old.beginBody = { begin.await() }; old.stopBody = { stop.await() }
        val newBegin = CompletableDeferred<Pair<Boolean, String>>()
        fresh.beginBody = { newBegin.await() }
        val service = PcmStreamService(this, { current }, Dispatchers.Unconfined, pause = {})
        val oldReader = Reader(); val newReader = Reader()
        service.startReader(oldReader); until { old.events.contains("begin") }
        service.stop(); until { old.events.contains("stop") }
        current = fresh; service.startReader(newReader)
        assertTrue(fresh.events.isEmpty())
        stop.complete(true to "ok")
        until { fresh.events.contains("begin") }
        assertTrue(service.status.value.active)
        assertEquals(1, oldReader.closes)
        newBegin.complete(true to "ok"); done(service)
        assertEquals(listOf("begin", "data", "stop"), fresh.events)
        assertEquals(1, newReader.closes)
    }

    @Test(timeout = 5000) fun cancelledBlockingReadCannotSendDataOrStopReplacementConnection() = runBlocking {
        val old = Link(); val fresh = Link(); var current = old
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reader = object : PcmSource.PcmReader {
            override fun read(out: ShortArray): Int { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); return out.size }
            override fun close() { closed.countDown() }
        }
        val service = PcmStreamService(this, { current }, Dispatchers.Default, pause = {})
        try {
            service.startReader(reader)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            old.current = false; service.stop("Disconnected")
            current = fresh; service.startReader(Reader())
            release.countDown(); done(service)
            assertEquals(listOf("begin"), old.events)
            assertEquals(listOf("begin", "data", "stop"), fresh.events)
            assertEquals(0L, closed.count)
        } finally { release.countDown(); service.stop() }
    }

    @Test(timeout = 5000) fun deviceMicKeepsAnOwnerUntilStop() = runBlocking {
        val link = Link(); val service = service(link)
        service.start(PcmStreamSpec(100000, true), "starting", "device mic")
        until { service.status.value.message == "device mic" }
        assertTrue(service.status.value.active)
        service.stop(); coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("begin", "stop"), link.events)
        assertFalse(service.status.value.active)
    }

    @Test(timeout = 5000) fun duplicateStartAndInvalidOrUnavailableHardwareDoNotCreateSources() = runBlocking {
        var creates = 0
        val link = Link(); val service = service(link)
        service.start(PcmStreamSpec(1), "bad", "bad") { creates++; null }
        assertTrue(link.events.isEmpty())
        val unavailable = PcmStreamService(this, { throw IOException("No confirmed speaker.") })
        unavailable.start(PcmStreamSpec(), "bad", "bad") { creates++; null }
        assertEquals("No confirmed speaker.", unavailable.status.value.message)
        service.start(PcmStreamSpec(100000, true), "start", "playing") { creates++; null }
        service.start(PcmStreamSpec(), "duplicate", "duplicate") { creates++; null }
        until { link.events.contains("begin") }
        assertEquals(1, creates)
        service.stop()
    }

    @Test(timeout = 5000) fun parentCancellationBeforeAndDuringPreparationClearsActive() = runBlocking {
        for (before in listOf(true, false)) {
            val parent = Job(); if (before) parent.cancel()
            val link = Link(); var preparing = false
            val service = PcmStreamService(CoroutineScope(coroutineContext + parent), { link }, Dispatchers.Unconfined)
            service.start(PcmStreamSpec(), "preparing", "playing") { preparing = true; awaitCancellation() }
            if (!before) { until { preparing }; parent.cancel() }
            parent.join(); done(service)
            assertTrue(link.events.isEmpty()); assertEquals("Stopped.", service.status.value.message)
        }
    }

    @Test(timeout = 5000) fun zeroReadBacksOffAndInvalidCountsOrCloseFailureAreErrors() = runBlocking {
        for (badCount in listOf(true, false)) {
            val link = Link(); val waits = mutableListOf<Long>(); var reads = 0; var closes = 0
            val reader = object : PcmSource.PcmReader {
                override fun read(out: ShortArray): Int = if (reads++ == 0) 0 else if (badCount) out.size + 1 else -1
                override fun close() { closes++; if (!badCount) throw IOException("close error") }
            }
            val service = PcmStreamService(this, { link }, Dispatchers.Unconfined, pause = { waits += it })
            service.startReader(reader); done(service)
            assertEquals(listOf(5L), waits); assertEquals(1, closes)
            assertFalse(service.status.value.message == "Finished.")
            assertEquals(listOf("begin", "stop"), link.events)
        }
    }

    @Test(timeout = 5000) fun failedStopIsNotReportedAsSuccessfulCompletion() = runBlocking {
        val link = Link().apply { stopBody = { false to "stop failed" } }
        val reader = Reader(); val service = service(link)
        service.startReader(reader); done(service)
        assertEquals("stop failed", service.status.value.message); assertEquals(1, reader.closes)
    }

    @Test(timeout = 5000) fun sstvEncoderCooperativelyStopsWithoutChangingNormalSamples() {
        val image = Sstv.Image(2, 2)
        var checks = 0
        try {
            Sstv.encode(image, Sstv.Mode.ROBOT36) { if (++checks == 3) throw CancellationException("fixture") }
            fail("Encoder ignored cancellation")
        } catch (_: CancellationException) { assertEquals(3, checks) }
        assertArrayEquals(Sstv.encode(image, Sstv.Mode.ROBOT36), Sstv.encode(image, Sstv.Mode.ROBOT36) {})
    }
}
