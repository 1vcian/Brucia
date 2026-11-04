package app.brucia

import app.brucia.files.*
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.zip.CRC32

class SessionFileLinkTest {
    private class Wire : Transport {
        val frames = mutableListOf<JSONObject>()
        var onWrite: (JSONObject) -> Unit = {}
        override var isOpen = true
        override fun write(bytes: ByteArray) {
            val frame = JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))
            frames += frame; onWrite(frame)
        }
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
    }
    private class Fixture(transactional: Boolean = true) {
        val wire = Wire()
        val session = CommandSession(CompanionClient(wire), wire)
        val reader = DeviceFileReader(timeoutMs = 100)
        val mutex = Mutex()
        var current = true
        val link = SessionFileLink(session, reader, mutex, { current }, writeTimeoutMs = 40, transactionalUploads = transactional, commitTimeoutMs = 40)
        fun ack(frame: JSONObject, size: Int, checksum: Long? = null, ok: Boolean = true) {
            val data = JSONObject().put("ok", ok).put("size", size)
            checksum?.let { data.put("crc32", it) }
            session.route(JSONObject().put("v", 0).put("t", Kind.ACK).put("id", frame.getInt("id")).put("d", data))
        }
    }

    @Test(timeout = 5000) fun readsOnlyMatchingSizeAndCrcOnRealSession() = runBlocking {
        for (valid in listOf(true, false)) {
            val f = Fixture(); val bytes = "fixture".toByteArray()
            f.wire.onWrite = { frame ->
                val id = frame.getInt("id")
                f.reader.chunk(id + 100, 0, "YmFk") // unrelated data is ignored
                f.reader.chunk(id, 0, Base64.getEncoder().encodeToString(bytes))
                val crc = CRC32().apply { update(bytes) }.value
                f.ack(frame, bytes.size, if (valid) crc else crc xor 1)
            }
            val result = runCatching { f.link.read("/fixture") }
            if (valid) assertArrayEquals(bytes, result.getOrThrow())
            else assertTrue(result.exceptionOrNull()!!.message!!.contains("checksum"))
            assertFalse(f.session.hasPending)
        }
    }

    @Test(timeout = 5000) fun stagedChunkedAndEmptyUploadRequireMatchingCommit() = runBlocking {
        for (size in listOf(0, 7000)) {
            val f = Fixture(); val received = mutableListOf<Byte>()
            var token = ""; var checksum = 0L
            f.wire.onWrite = { frame ->
                val words = frame.getJSONObject("d").getString("cmd").split(' ', limit = 5)
                when (words[0]) {
                    "fs_upload_begin" -> {
                        token = words[1]; checksum = words[3].toLong()
                        assertEquals("/hello world.txt", words[4])
                        assertEquals(size, words[2].toInt())
                        uploadAck(f, frame, token, 0)
                    }
                    "fs_upload_chunk" -> {
                        assertEquals(token, words[1])
                        assertEquals(received.size, words[2].toInt())
                        received += Base64.getDecoder().decode(words[3]).toList()
                        uploadAck(f, frame, token, received.size)
                    }
                    "fs_upload_commit" -> {
                        assertEquals(token, words[1])
                        assertEquals(checksum, CRC32().apply { update(received.toByteArray()) }.value)
                        uploadAck(f, frame, token, received.size, checksum, true)
                    }
                    else -> fail("Unexpected command ${words[0]}")
                }
            }
            val bytes = ByteArray(size) { (it % 251).toByte() }
            f.link.write("hello world.txt", bytes)
            assertArrayEquals(bytes, received.toByteArray())
            assertEquals(if (size == 0) 2 else 5, f.wire.frames.size)
            assertFalse(f.session.hasPending)
        }
    }

    private fun uploadAck(f: Fixture, frame: JSONObject, token: String, size: Int,
                          crc: Long? = null, committed: Boolean = false, ok: Boolean = true) {
        val data = JSONObject().put("ok", ok).put("token", token).put("size", size)
        if (committed) data.put("committed", true)
        if (crc != null) data.put("crc32", crc)
        f.session.route(JSONObject().put("t", Kind.ACK).put("id", frame.getInt("id")).put("d", data))
    }

    @Test(timeout = 5000) fun incorrectBeginAckAbortsWithoutSendingChunks() = runBlocking {
        for (wrongToken in listOf(true, false)) {
            val f = Fixture()
            f.wire.onWrite = { frame ->
                val words = frame.getJSONObject("d").getString("cmd").split(' ')
                uploadAck(f, frame, if (wrongToken) "wrong" else words[1], if (wrongToken) 0 else 1)
            }
            assertTrue(runCatching { f.link.write("/fixture", ByteArray(7000)) }.isFailure)
            assertEquals(listOf("fs_upload_begin", "fs_upload_abort"), f.wire.frames.map {
                it.getJSONObject("d").getString("cmd").substringBefore(' ')
            })
            assertFalse(f.session.hasPending)
        }
    }

    @Test(timeout = 5000) fun uploadTimeoutAndCancellationAbortWithoutContinuing() = runBlocking {
        for (cancel in listOf(false, true)) {
            val f = Fixture()
            val job = launch { runCatching { f.link.write("/fixture", ByteArray(7000)) } }
            until { f.wire.frames.isNotEmpty() }
            if (cancel) job.cancel()
            job.join()
            assertEquals(2, f.wire.frames.size)
            assertTrue(f.wire.frames.last().getJSONObject("d").getString("cmd").startsWith("fs_upload_abort "))
            val begin = f.wire.frames.first()
            uploadAck(f, begin, begin.getJSONObject("d").getString("cmd").split(' ')[1], 0)
            assertEquals(2, f.wire.frames.size)
            assertFalse(f.session.hasPending)
        }
    }

    @Test(timeout = 5000) fun olderFirmwareReadStillWorksButUploadNeverFallsBackToTruncate() = runBlocking {
        val f = Fixture(transactional = false)
        assertTrue(runCatching { f.link.write("/fixture", ByteArray(0)) }.exceptionOrNull()!!.message!!.contains("transactional"))
        assertTrue(f.wire.frames.isEmpty())
        f.wire.onWrite = { f.ack(it, 0, 0) }
        assertArrayEquals(ByteArray(0), f.link.read("/fixture"))
    }

    @Test(timeout = 5000) fun badChunkOrCommitAckCannotReportSaved() = runBlocking {
        for (failure in listOf("chunk", "crc", "committed", "error")) {
            val f = Fixture()
            f.wire.onWrite = { frame ->
                val words = frame.getJSONObject("d").getString("cmd").split(' ')
                val token = words[1]
                when (words[0]) {
                    "fs_upload_begin", "fs_upload_abort" -> uploadAck(f, frame, token, 0)
                    "fs_upload_chunk" -> uploadAck(f, frame, token, if (failure == "chunk") 1 else 3)
                    "fs_upload_commit" -> uploadAck(f, frame, token, 3,
                        if (failure == "crc") 0 else 0x352441c2, failure != "committed", failure != "error")
                }
            }
            assertTrue(runCatching { f.link.write("/fixture", "abc".toByteArray()) }.isFailure)
            if (failure == "chunk") assertEquals(3, f.wire.frames.size)
            assertFalse(f.session.hasPending)
        }
    }

    @Test(timeout = 5000) fun disconnectNeverAbortsOnReplacementSession() = runBlocking {
        val f = Fixture()
        f.wire.onWrite = { frame ->
            val token = frame.getJSONObject("d").getString("cmd").split(' ')[1]
            f.current = false
            uploadAck(f, frame, token, 0)
        }
        assertTrue(runCatching { f.link.write("/fixture", ByteArray(3)) }.isFailure)
        assertEquals(1, f.wire.frames.size)
        assertFalse(f.session.hasPending)
    }

    @Test(timeout = 5000) fun lostOrMismatchedCommitReplyRequiresVerificationWithoutRetry() = runBlocking {
        for (lost in listOf(true, false)) {
            val f = Fixture()
            f.wire.onWrite = { frame ->
                val words = frame.getJSONObject("d").getString("cmd").split(' ')
                if (words[0] != "fs_upload_commit") uploadAck(f, frame, words[1], 0)
                else if (!lost) uploadAck(f, frame, "wrong-token", 0, 0, true)
            }
            val error = runCatching { f.link.write("/fixture", ByteArray(0)) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("verify the destination before retrying"))
            assertEquals(listOf("fs_upload_begin", "fs_upload_commit", "fs_upload_abort"),
                f.wire.frames.map { it.getJSONObject("d").getString("cmd").substringBefore(' ') })
            assertFalse(f.session.hasPending)
        }
    }

    @Test(timeout = 5000) fun traversalReservedNamesAndInvalidPathComponentsRejectBeforeBegin() = runBlocking {
        val f = Fixture()
        for (path in listOf("/", "/a/../b", "/a//b", "/a/", "/a.", "/.BRUCIA-UPLOAD.part", "/a\\b", "/a:b", "/" + "x".repeat(192))) {
            assertTrue(path, runCatching { f.link.write(path, ByteArray(0)) }.isFailure)
        }
        assertTrue(f.wire.frames.isEmpty())
    }

    @Test(timeout = 5000) fun queuedWireOperationsRecheckSessionAndRespectCancellation() = runBlocking {
        for (cancel in listOf(false, true)) {
            val f = Fixture(); f.mutex.lock()
            val queued = launch { runCatching { f.link.read("/fixture") } }
            yield()
            if (cancel) queued.cancel() else f.current = false
            f.mutex.unlock(); queued.join()
            assertTrue(f.wire.frames.isEmpty()); assertFalse(f.session.hasPending)
        }
    }

    @Test(timeout = 5000) fun cancelledReadReleasesRequestAndRejectsLateData() = runBlocking {
        val f = Fixture()
        val job = launch { f.link.read("/fixture") }
        until { f.wire.frames.isNotEmpty() }
        job.cancelAndJoin()
        val id = f.wire.frames.single().getInt("id")
        f.reader.chunk(id, 0, "YWJj"); f.ack(f.wire.frames.single(), 3, 0x352441c2L)
        assertFalse(f.session.hasPending)
        f.wire.onWrite = { f.ack(it, 0, 0) }
        assertArrayEquals(ByteArray(0), f.link.read("/new"))
    }

    @Test(timeout = 5000) fun invalidPathsOversizeAndClosedSessionSendNothing() = runBlocking {
        val f = Fixture()
        assertTrue(runCatching { f.link.read("/bad\npath") }.isFailure)
        assertTrue(runCatching { f.link.write("/fixture", ByteArray(SessionFileLink.MAX_BYTES + 1)) }.isFailure)
        f.session.close()
        assertTrue(runCatching { f.link.write("/fixture", ByteArray(0)) }.isFailure)
        assertTrue(f.wire.frames.isEmpty()); assertFalse(f.session.hasPending)
    }
}
