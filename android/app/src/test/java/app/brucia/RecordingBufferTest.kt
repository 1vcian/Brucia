package app.brucia

import app.brucia.audio.RecordingBuffer
import app.brucia.audio.RecordingBuffer.Append
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RecordingBufferTest {
    @Test fun failedExportKeepsSamplesAndSuccessfulExportClearsOnlyItsOwnRecording() {
        val buffer = RecordingBuffer(8)
        buffer.start(); buffer.append(byteArrayOf(1, 2))
        val failed = buffer.snapshot()
        assertTrue(buffer.hasData)
        assertArrayEquals(failed.bytes, buffer.snapshot().bytes)
        assertTrue(buffer.commit(failed))
        assertFalse(buffer.hasData)
    }
    @Test fun lateExportCompletionCannotDeleteANewerRecording() {
        val buffer = RecordingBuffer(8)
        buffer.start(); buffer.append(byteArrayOf(1, 2)); val old = buffer.snapshot()
        buffer.cancel(); buffer.start(); buffer.append(byteArrayOf(3, 4))
        assertFalse(buffer.commit(old))
        assertArrayEquals(byteArrayOf(3, 4), buffer.take())
    }
    @Test fun limitStopsExactlyAndPreservesAudioUntilSaved() {
        val buffer = RecordingBuffer(6)
        assertTrue(buffer.start())
        buffer.append(byteArrayOf(1, 2, 3, 4))
        buffer.append(byteArrayOf(5, 6, 7, 8))
        assertFalse(buffer.active)
        assertTrue(buffer.hasData)
        assertFalse(buffer.start()) // never overwrite an unsaved recording
        buffer.append(byteArrayOf(9, 10))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buffer.take())
        assertFalse(buffer.hasData)
        assertTrue(buffer.start())
    }
    @Test fun stoppingOrDisconnectingPreservesDataForOfflineExport() {
        val buffer = RecordingBuffer(8)
        buffer.start(); buffer.append(byteArrayOf(1, 2)); buffer.stop()
        assertFalse(buffer.active)
        assertTrue(buffer.hasData)
        assertArrayEquals(byteArrayOf(1, 2), buffer.take())
    }
    @Test fun explicitCancellationDiscardsDataAndOddPcmIsRejected() {
        val buffer = RecordingBuffer(8)
        buffer.start(); buffer.append(byteArrayOf(1)); assertFalse(buffer.hasData)
        buffer.append(byteArrayOf(1, 2)); buffer.cancel()
        assertFalse(buffer.active); assertFalse(buffer.hasData)
        assertTrue(buffer.start())
    }
    @Test fun appendReportsItsOwnOutcome() {
        val buffer = RecordingBuffer(6)
        assertEquals(Append.INACTIVE, buffer.append(byteArrayOf(1, 2)))
        buffer.start()
        assertEquals(Append.REJECTED, buffer.append(byteArrayOf(1)))
        assertEquals(Append.APPENDED, buffer.append(byteArrayOf(1, 2)))
        assertEquals(Append.LIMIT_REACHED, buffer.append(byteArrayOf(3, 4, 5, 6, 7, 8)))
        assertEquals(Append.INACTIVE, buffer.append(byteArrayOf(9, 10)))
        val st = buffer.state.value
        assertFalse(st.active); assertTrue(st.hasData); assertTrue(st.limitReached)
    }

    /**
     * Producer (IO read loop) appends k chunks; one Main operation (stop, cancel or export snapshot)
     * is placed at every possible position in that sequence. In each ordering the published state
     * must match what Main last did, and LIMIT_REACHED (the "Recording limit reached" text) may only
     * come from a chunk that really filled the buffer, never from a Stop that made `active` false.
     */
    @Test fun everyInterleavingOfProducerAndMainYieldsConsistentState() {
        val ops = listOf<Pair<String, (RecordingBuffer) -> Unit>>(
            "stop" to { b -> b.stop() },
            "cancel" to { b -> b.cancel() },
            "snapshot" to { b -> b.snapshot() },
        )
        val k = 4
        for ((name, op) in ops) for (limitChunks in listOf(2, 10)) for (at in 0..k) {
            val buffer = RecordingBuffer(2 * limitChunks)
            assertTrue(buffer.start())
            val outcomes = mutableListOf<Append>()
            for (j in 0..k) {
                if (j == at) op(buffer)
                if (j < k) outcomes += buffer.append(byteArrayOf(j.toByte(), 0), seq = j.toLong(), dropped = 0)
            }
            val stored = outcomes.count { it == Append.APPENDED || it == Append.LIMIT_REACHED }
            val expectStored = minOf(at, limitChunks)
            val where = "$name at $at limit $limitChunks: $outcomes"
            assertEquals(where, expectStored, stored)
            assertTrue(where, outcomes.drop(at).all { it == Append.INACTIVE })
            val limitHit = at >= limitChunks
            assertEquals(where, if (limitHit) 1 else 0, outcomes.count { it == Append.LIMIT_REACHED })
            val st = buffer.state.value
            assertFalse(where, st.active)
            assertEquals(where, name != "cancel" && expectStored > 0, st.hasData)
            assertEquals(where, name != "cancel" && limitHit, st.limitReached)
            assertEquals(where, buffer.hasData, st.hasData)
        }
    }

    /** Same race with two real threads, ordered by latches (no sleeps): Stop lands between chunks. */
    @Test fun stopBetweenProducerChunksIsNeverOverwrittenByTheProducer() {
        val buffer = RecordingBuffer(64)
        buffer.start()
        val firstDone = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val outcomes = arrayOfNulls<Append>(2)
        val producer = Thread {
            outcomes[0] = buffer.append(byteArrayOf(1, 2), seq = 0, dropped = 0)
            firstDone.countDown()
            stopped.await(5, TimeUnit.SECONDS)
            outcomes[1] = buffer.append(byteArrayOf(3, 4), seq = 1, dropped = 0)
        }
        producer.start()
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        buffer.stop()
        val afterStop = buffer.state.value
        stopped.countDown()
        producer.join(5000)
        assertEquals(Append.APPENDED, outcomes[0])
        assertEquals(Append.INACTIVE, outcomes[1])
        assertEquals(afterStop, buffer.state.value)
        assertFalse(buffer.state.value.active)
        assertFalse(buffer.state.value.limitReached)
        assertArrayEquals(byteArrayOf(1, 2), buffer.snapshot().bytes)
    }

    /** Invariant under free-running contention: the last published state equals the buffer. */
    @Test fun publishedStateMatchesBufferAfterContention() {
        val buffer = RecordingBuffer(2 * 100_000)
        val go = CountDownLatch(1)
        val producer = Thread {
            go.await(5, TimeUnit.SECONDS)
            for (i in 0 until 20_000) buffer.append(byteArrayOf(1, 2), seq = i.toLong(), dropped = 0)
        }
        producer.start()
        go.countDown()
        for (i in 0 until 2_000) { if (i % 3 == 0) buffer.cancel(); buffer.start(); if (i % 2 == 0) buffer.stop() }
        producer.join(10_000)
        val st = buffer.state.value
        assertEquals(buffer.active, st.active)
        assertEquals(buffer.hasData, st.hasData)
    }

    @Test fun consecutiveSequenceWithoutOverrunsReportsZeroGaps() {
        val buffer = RecordingBuffer(64)
        buffer.start()
        for (s in 0L..3L) buffer.append(byteArrayOf(1, 2), seq = s, dropped = 0)
        val integrity = buffer.snapshot().integrity
        assertEquals(4, integrity.chunks)
        assertEquals(0, integrity.gapsDetected)
        assertEquals("gaps detected: 0", integrity.summary())
    }

    @Test fun sequenceJumpsAndRestartsAreCountedAsGaps() {
        val buffer = RecordingBuffer(64)
        buffer.start()
        buffer.append(byteArrayOf(1, 2), seq = 5, dropped = 0)
        buffer.append(byteArrayOf(1, 2), seq = 6, dropped = 0)
        buffer.append(byteArrayOf(1, 2), seq = 9, dropped = 0) // 7, 8 lost on the link
        buffer.append(byteArrayOf(1, 2), seq = 0, dropped = 0) // stream restarted mid-recording
        buffer.append(byteArrayOf(1, 2), seq = 1, dropped = 0)
        assertEquals(2, buffer.snapshot().integrity.seqGaps)
        assertEquals("gaps detected: 2", buffer.snapshot().integrity.summary())
    }

    @Test fun deviceOverrunsAreCountedAsDeltasFromTheStreamBaseline() {
        val buffer = RecordingBuffer(64)
        // Before recording the stream already lost 256 samples; that is not this recording's loss.
        assertEquals(Append.INACTIVE, buffer.append(byteArrayOf(1, 2), seq = 0, dropped = 0))
        buffer.append(byteArrayOf(1, 2), seq = 1, dropped = 256)
        buffer.start()
        buffer.append(byteArrayOf(1, 2), seq = 2, dropped = 256)
        buffer.append(byteArrayOf(1, 2), seq = 3, dropped = 512)
        buffer.append(byteArrayOf(1, 2), seq = 4, dropped = 512)
        val integrity = buffer.snapshot().integrity
        assertEquals(0, integrity.seqGaps)
        assertEquals(1, integrity.overrunChunks)
        assertEquals(256L, integrity.droppedSamples)
        assertEquals("gaps detected: 1 (256 samples dropped on device)", integrity.summary())
    }

    @Test fun newSubscriptionResetsTheOverrunBaseline() {
        val buffer = RecordingBuffer(64)
        buffer.append(byteArrayOf(1, 2), seq = 7, dropped = 1024)
        buffer.streamRestarted()
        buffer.start()
        buffer.append(byteArrayOf(1, 2), seq = 1, dropped = 0) // seq 0 lost; counters restarted
        buffer.append(byteArrayOf(1, 2), seq = 2, dropped = 0)
        assertEquals(0L, buffer.snapshot().integrity.droppedSamples)
    }

    @Test fun absentFieldsMeanUnknownNotZero() {
        val old = RecordingBuffer(64)
        old.start()
        old.append(byteArrayOf(1, 2)); old.append(byteArrayOf(3, 4))
        val unknown = old.snapshot().integrity
        assertNull(unknown.seqGaps); assertNull(unknown.droppedSamples); assertNull(unknown.gapsDetected)
        assertEquals("gaps: unknown (firmware sends no sequence numbers)", unknown.summary())

        val seqOnly = RecordingBuffer(64)
        seqOnly.start()
        seqOnly.append(byteArrayOf(1, 2), seq = 0); seqOnly.append(byteArrayOf(1, 2), seq = 2)
        assertEquals("gaps detected: 1 (device overruns not reported)", seqOnly.snapshot().integrity.summary())
    }

    @Test fun integrityResetsWithEachRecording() {
        val buffer = RecordingBuffer(64)
        buffer.start()
        buffer.append(byteArrayOf(1, 2), seq = 0, dropped = 0)
        buffer.append(byteArrayOf(1, 2), seq = 3, dropped = 0)
        buffer.cancel()
        buffer.start()
        buffer.append(byteArrayOf(1, 2), seq = 4, dropped = 0)
        val integrity = buffer.snapshot().integrity
        assertEquals(1, integrity.chunks)
        assertEquals(0, integrity.seqGaps)
    }
}
