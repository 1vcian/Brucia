package app.brucia

import app.brucia.files.DeviceFileReader
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceFileReaderTest {
    @Test fun rejectsSameSizeCorruptionAndMissingChecksum() = runVirtual {
        for (checksum in listOf(-1L, 0L)) {
            val reader = DeviceFileReader()
            assertNull(reader.read(101) {
                reader.chunk(101, 0, "YWJj")
                reader.reply(101, true, 3, "", checksum)
            }.bytes)
        }
    }
    @Test fun validatesChunksAndCompletionSize() = runVirtual {
        val reader = DeviceFileReader()
        val result = reader.read(101) {
            reader.chunk(101, 0, "YWJj")
            reader.chunk(101, 1, "ZA==")
            reader.reply(101, true, 4, "", 0xed82cd11L)
        }
        assertArrayEquals("abcd".toByteArray(), result.bytes)
    }

    @Test fun emptyFileRequiresExplicitZeroSizeAck() = runVirtual {
        val reader = DeviceFileReader()
        assertArrayEquals(ByteArray(0), reader.read(101) { reader.reply(101, true, 0, "", 0) }.bytes)
        assertNull(reader.read(102) { reader.reply(102, true, -1, "") }.bytes)
    }

    @Test fun neverAcceptsPartialDataAsSuccess() = runVirtual {
        val reader = DeviceFileReader()
        val result = reader.read(101) {
            reader.chunk(101, 0, "YWJj")
            reader.reply(101, true, 4, "", 0xed82cd11L)
        }
        assertNull(result.bytes)
        assertEquals("File size mismatch", result.error)
    }

    @Test fun rejectsMalformedOutOfOrderAndDuplicateChunks() = runVirtual {
        for (mode in 0..2) {
            val reader = DeviceFileReader()
            val result = reader.read(101) {
                when (mode) {
                    0 -> reader.chunk(101, 0, "?bad")
                    1 -> reader.chunk(101, 1, "YWJj")
                    else -> { reader.chunk(101, 0, "YWJj"); reader.chunk(101, 0, "YWJj") }
                }
                reader.reply(101, true, 3, "")
            }
            assertNull("Mode $mode", result.bytes)
            assertTrue(result.error.isNotBlank())
        }
    }

    @Test fun boundsBothDecodedAndEncodedInput() = runVirtual {
        for (encoded in listOf("YWJjZA==", "A".repeat(8193))) {
            val reader = DeviceFileReader(maxBytes = 3)
            assertNull(reader.read(101) { reader.chunk(101, 0, encoded) }.bytes)
        }
    }

    @Test fun ignoresUnrelatedChunksAndRepliesIncludingLegacyIdZero() = runVirtual {
        val reader = DeviceFileReader()
        val result = reader.read(101) {
            reader.chunk(0, 0, "YmFk")
            reader.chunk(999, 0, "YmFk")
            reader.reply(999, true, 3, "")
            reader.chunk(101, 0, "b2s=")
            reader.reply(101, true, 2, "", 0x79dcdd47L)
        }
        assertArrayEquals("ok".toByteArray(), result.bytes)
    }

    @Test fun missingReplyTimesOutAndNextTransferStillWorks() = runVirtual {
        val reader = DeviceFileReader(timeoutMs = 20)
        assertEquals("File download timed out", reader.read(101) {}.error)
        assertArrayEquals(ByteArray(0), reader.read(102) { reader.reply(102, true, 0, "", 0) }.bytes)
    }

    @Test fun concurrentRequestsAreSerialized() = runVirtual {
        val reader = DeviceFileReader()
        val sent = mutableListOf<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { reader.read(101) { sent.add(101) } }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            reader.read(102) { sent.add(102); reader.reply(102, true, 0, "", 0) }
        }
        assertEquals(listOf(101), sent)
        reader.reply(101, true, 0, "", 0)
        assertNotNull(first.await().bytes)
        assertNotNull(second.await().bytes)
        assertEquals(listOf(101, 102), sent)
    }

    @Test fun disconnectFailsActiveAndQueuedTransfersWithoutSendingQueuedCommand() = runVirtual {
        val reader = DeviceFileReader()
        val first = async(start = CoroutineStart.UNDISPATCHED) { reader.read(101) {} }
        var sentSecond = false
        val second = async(start = CoroutineStart.UNDISPATCHED) { reader.read(102) { sentSecond = true } }
        reader.disconnect()
        assertEquals("Disconnected", first.await().error)
        assertEquals("Disconnected", second.await().error)
        assertFalse(sentSecond)
        assertNotNull(reader.read(103) { reader.reply(103, true, 0, "", 0) }.bytes)
    }

    @Test fun cancellationReleasesReaderAndLateChunksCannotContaminateNextPull() = runVirtual {
        val reader = DeviceFileReader()
        val first = async(start = CoroutineStart.UNDISPATCHED) { reader.read(101) {} }
        first.cancel(); first.join()
        val result = reader.read(102) {
            reader.chunk(101, 0, "YmFk")
            reader.reply(101, true, 3, "")
            reader.reply(102, true, 0, "", 0)
        }
        assertArrayEquals(ByteArray(0), result.bytes)
    }
}
