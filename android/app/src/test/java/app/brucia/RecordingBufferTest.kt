package app.brucia

import app.brucia.audio.RecordingBuffer
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
}
