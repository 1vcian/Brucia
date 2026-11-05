package app.brucia

import app.brucia.audio.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** fm_audio_data accepted-count flow control against fake devices (partial, full, old firmware). */
class PcmFlowControlTest {
    /** Device with a bounded ring drained at [drainPerMs] samples per fake millisecond. */
    private class RingDevice(
        val capacity: Int, val clock: () -> Long, val drainPerMs: Int,
        val reports: Boolean = true, var lost: Long = 0,
    ) : PcmStreamLink {
        val events = CopyOnWriteArrayList<String>()
        val requests = mutableListOf<Int>()
        val queued = mutableListOf<Short>()
        var replies = mutableListOf<(ShortArray, Int) -> PcmDataReply>() // scripted overrides, in order
        private var used = 0
        private var drainedAt = 0L
        override fun isCurrent() = true
        override suspend fun begin(): Pair<Boolean, String> { events += "begin"; drainedAt = clock(); return true to "ok" }
        override suspend fun stop(): Pair<Boolean, String> { events += "stop"; return true to "ok" }
        override suspend fun data(samples: ShortArray, count: Int): PcmDataReply {
            events += "data"; requests += count
            replies.removeFirstOrNull()?.let { return it(samples, count) }
            val now = clock()
            used = maxOf(0, used - ((now - drainedAt) * drainPerMs).toInt()); drainedAt = now
            val accepted = minOf(count, capacity - used)
            for (i in 0 until accepted) queued += samples[i]
            used += accepted
            return if (reports) PcmDataReply(true, "", accepted, count, capacity - used, lost)
            else { for (i in accepted until count) queued += samples[i]; PcmDataReply(true, "") }
        }
    }

    private fun source(n: Int) = PcmStreamSource(PcmSource.arrayReader(ShortArray(n) { (it % 30000).toShort() }), n.toLong())
    private suspend fun done(service: PcmStreamService) = until { !service.status.value.active }

    @Test(timeout = 5000) fun partialFramesResendOnlyTheRemainderAndDeliverEverySampleInOrder() = runVirtual {
        var clock = 0L; val waits = mutableListOf<Long>()
        // Drains at half real time, so the ring fills and frames are accepted partially.
        val device = RingDevice(capacity = 1500, clock = { clock }, drainPerMs = 8)
        val service = PcmStreamService(this, { device }, virtualUnconfined, { clock }, { waits += it; clock += it })
        val src = source(6000)
        service.start(PcmStreamSpec(fmKHz = 100000), "prep", "Transmitting.") { src }
        done(service)
        assertEquals(List(6000) { (it % 30000).toShort() }, device.queued)
        assertTrue("expected partial resends: ${device.requests}", device.requests.any { it in 1..1023 } && device.requests.size > 6)
        assertEquals("stop", device.events.last())
        assertEquals(PcmDelivery.REPORTED, service.status.value.delivery)
        assertEquals("Finished.", service.status.value.message)
        assertEquals(375L, service.status.value.elapsedMs) // accepted samples only: 6000 / 16 kHz
    }

    @Test(timeout = 5000) fun fullRingWaitsForDrainThenResendsTheSameFrame() = runVirtual {
        var clock = 0L; val waits = mutableListOf<Long>()
        val device = RingDevice(capacity = 8191, clock = { clock }, drainPerMs = 16)
        device.replies = mutableListOf(
            { _, n -> PcmDataReply(true, "ring full: accepted 0 of $n samples", 0, n, 0, 0) },
            { _, n -> PcmDataReply(true, "ring full: accepted 0 of $n samples", 0, n, 0, 0) },
        )
        val service = PcmStreamService(this, { device }, virtualUnconfined, { clock }, { waits += it; clock += it })
        service.start(PcmStreamSpec(fmKHz = 100000), "prep", "playing") { source(1024) }
        done(service)
        assertEquals(listOf(1024, 1024, 1024), device.requests)
        assertEquals(1024, device.queued.size)
        assertEquals(listOf(64L, 64L), waits.take(2)) // drain wait for 1024 samples before each resend
        assertEquals("Finished.", service.status.value.message)
    }

    @Test(timeout = 5000) fun ringThatNeverDrainsFailsAfterBoundedRetriesAndStillStops() = runVirtual {
        val device = RingDevice(capacity = 0, clock = { 0L }, drainPerMs = 0)
        val service = PcmStreamService(this, { device }, virtualUnconfined, pause = {})
        service.start(PcmStreamSpec(fmKHz = 100000), "prep", "playing") { source(1024) }
        done(service)
        // 64 ms drain waits for the 1024-sample remainder until 2 s pass with nothing accepted.
        assertEquals(33, device.requests.size)
        assertEquals("Device stopped accepting audio; 1024 samples not delivered.", service.status.value.message)
        assertEquals("stop", device.events.last())
    }

    @Test(timeout = 5000) fun oldFirmwareKeepsSingleSendPerFrameAndIsLabelledUnknownForFmOnly() = runVirtual {
        for (fm in listOf(true, false)) {
            val device = RingDevice(capacity = 100, clock = { 0L }, drainPerMs = 0, reports = false)
            val service = PcmStreamService(this, { device }, virtualUnconfined, pause = {})
            val spec = if (fm) PcmStreamSpec(fmKHz = 100000) else PcmStreamSpec()
            service.start(spec, "prep", "playing") { source(3000) }
            done(service)
            assertEquals(if (fm) listOf(1024, 1024, 952) else listOf(2048, 952), device.requests)
            assertEquals(3000, device.queued.size)
            val state = service.status.value
            if (fm) {
                assertEquals(PcmDelivery.UNKNOWN, state.delivery)
                assertEquals("Finished; delivery unknown (firmware does not report accepted samples).", state.message)
            } else assertEquals("Finished.", state.message) // speaker/SSTV messages unchanged
        }
    }

    @Test(timeout = 5000) fun deviceReportedLossIsSurfacedWhilePlayingAndAtTheEnd() = runVirtual {
        val device = RingDevice(capacity = 100000, clock = { 0L }, drainPerMs = 0, lost = 37)
        lateinit var service: PcmStreamService
        var midStream = ""
        device.replies = mutableListOf(
            { _, n -> PcmDataReply(true, "", n, n, 5000, 37) },
            { _, n -> midStream = service.status.value.message; PcmDataReply(true, "", n, n, 5000, 37) },
        )
        service = PcmStreamService(this, { device }, virtualUnconfined, pause = {})
        service.start(PcmStreamSpec(fmKHz = 100000), "prep", "Transmitting.") { source(2048) }
        done(service)
        assertEquals("Transmitting. Device dropped 37 samples.", midStream)
        assertEquals(37L, service.status.value.deviceDropped)
        assertEquals("Finished; device dropped 37 samples.", service.status.value.message)
    }

    @Test(timeout = 5000) fun knownFreeSpaceDelaysTheNextFrameInsteadOfOverfilling() = runVirtual {
        val waits = mutableListOf<Long>()
        val device = RingDevice(capacity = 100000, clock = { 0L }, drainPerMs = 0)
        device.replies = mutableListOf(
            { _, n -> PcmDataReply(true, "", n, n, 100, 0) },
            { _, n -> PcmDataReply(true, "", n, n, 8000, 0) },
        )
        // Fixed clock: nothing is estimated to drain, so the 924-sample deficit is waited out.
        val service = PcmStreamService(this, { device }, virtualUnconfined, { 0L }, { waits += it })
        service.start(PcmStreamSpec(fmKHz = 100000), "prep", "playing") { source(2048) }
        done(service)
        assertEquals(listOf(64L, 57L, 128L), waits)
        assertEquals(listOf(1024, 1024), device.requests)
    }

    @Test(timeout = 5000) fun impossibleCountsAreProtocolErrorsNotDelivery() = runVirtual {
        val bad = listOf<(ShortArray, Int) -> PcmDataReply>(
            { _, n -> PcmDataReply(true, "", n + 1, n, 0, 0) },
            { _, n -> PcmDataReply(true, "", -1, n, 0, 0) },
            { _, n -> PcmDataReply(true, "", n, n - 1, 0, 0) },
        )
        for (reply in bad) {
            val device = RingDevice(capacity = 100000, clock = { 0L }, drainPerMs = 0)
            device.replies = mutableListOf(reply)
            val service = PcmStreamService(this, { device }, virtualUnconfined, pause = {})
            service.start(PcmStreamSpec(fmKHz = 100000), "prep", "playing") { source(1024) }
            done(service)
            assertEquals("Device reported an invalid PCM sample count.", service.status.value.message)
            assertEquals(listOf("begin", "data", "stop"), device.events)
        }
    }
}
