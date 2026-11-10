package app.brucia

import app.brucia.wordlist.DictionaryFormatException
import app.brucia.wordlist.WordlistBusyException
import app.brucia.wordlist.WordlistStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** F37/F36: manual wordlist import is staged, bounded and never destroys the previous list. */
class WordlistImportTest {
    private val dir: File = Files.createTempDirectory("wordlist-import").toFile()
    private val previous = "previous-password\nsecond\n".toByteArray()

    @After fun cleanup() { dir.deleteRecursively() }

    private fun seed(): File = File(dir, "list.txt").apply { writeBytes(previous) }

    /** Provider that serves [data] and then throws, like a cloud document losing its connection. */
    private class FailingAfter(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) throw IOException("provider interrupted")
            val n = minOf(len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** An endless provider producing [chunk] forever (oversize / huge line inputs). */
    private class Endless(private val chunk: Byte, private val newlineEvery: Int = 0) : InputStream() {
        var served = 0L
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            for (i in off until off + len) {
                served++
                b[i] = if (newlineEvery > 0 && served % newlineEvery == 0L) '\n'.code.toByte() else chunk
            }
            return len
        }
    }

    private fun assertPreviousIntact() {
        assertArrayEquals(previous, File(dir, "list.txt").readBytes())
        assertEquals(listOf("list.txt"), dir.listFiles()!!.map { it.name })
        assertEquals(listOf("list.txt"), WordlistStore.listIn(dir).map { it.name })
    }

    private fun import(open: () -> InputStream, limit: Long = WordlistStore.MAX_BYTES,
                       isCancelled: () -> Boolean = { false },
                       sink: (File) -> OutputStream = { FileOutputStream(it) }) =
        WordlistStore.importInto(dir, "list.txt", isCancelled, limit, sink, open = open)

    @Test fun providerErrorAfterFewBytesKeepsPreviousList() {
        seed()
        val e = assertThrows(IOException::class.java) { import({ FailingAfter("bad\n".toByteArray()) }) }
        assertEquals("provider interrupted", e.message)
        assertPreviousIntact()
    }

    @Test fun oversizeIsRejectedWithoutReadingEverything() {
        seed()
        val src = Endless('a'.code.toByte(), newlineEvery = 10)
        val e = assertThrows(IOException::class.java) { import({ src }, limit = 1024 * 1024) }
        assertTrue(e.message!!.contains("exceeds"))
        assertTrue(src.served <= 1024 * 1024 + 64 * 1024)
        assertPreviousIntact()
    }

    @Test fun singleHugeLineIsRejectedInBoundedMemory() {
        seed()
        val src = Endless('a'.code.toByte())
        assertThrows(DictionaryFormatException::class.java) { import({ src }) }
        assertTrue("stopped at the first over-long line, read ${src.served}", src.served <= 128 * 1024)
        assertPreviousIntact()
    }

    @Test fun lateNulIsRejected() {
        seed()
        val data = ("pass\n".repeat(20_000) + "x\u0000y\n").toByteArray()
        assertThrows(DictionaryFormatException::class.java) { import({ data.inputStream() }) }
        assertPreviousIntact()
    }

    @Test fun emptyAndWebPageAreRejected() {
        seed()
        assertThrows(IOException::class.java) { import({ ByteArray(0).inputStream() }) }
        assertPreviousIntact()
        assertThrows(DictionaryFormatException::class.java) { import({ "<html>nope</html>".byteInputStream() }) }
        assertPreviousIntact()
    }

    @Test fun fullStorageWriteAndCloseFailuresKeepPreviousList() {
        seed()
        // Write fails after the first chunk (ENOSPC).
        assertThrows(IOException::class.java) {
            import({ "a\n".repeat(100_000).byteInputStream() }, sink = { f ->
                object : FileOutputStream(f) {
                    var n = 0L
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        n += len; if (n > 64 * 1024) throw IOException("No space left on device")
                        super.write(b, off, len)
                    }
                }
            })
        }
        assertPreviousIntact()
        // Close fails (deferred write error).
        assertThrows(IOException::class.java) {
            import({ "a\nb\n".byteInputStream() }, sink = { f ->
                object : FileOutputStream(f) { override fun close() { super.close(); throw IOException("close failed") } }
            })
        }
        assertPreviousIntact()
        // Sink silently drops bytes: the staged file is shorter than what was read.
        assertThrows(IOException::class.java) {
            import({ "a\nb\n".byteInputStream() }, sink = { f ->
                object : FileOutputStream(f) { override fun write(b: ByteArray, off: Int, len: Int) {} }
            })
        }
        assertPreviousIntact()
    }

    @Test fun cancellationOfSlowProviderKeepsPreviousList() {
        seed()
        val cancelled = AtomicBoolean(false)
        var reads = 0
        val slow = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5)
                if (++reads == 3) cancelled.set(true)
                b[off] = 'a'.code.toByte(); b[off + 1] = '\n'.code.toByte()
                return 2
            }
        }
        assertThrows(InterruptedException::class.java) { import({ slow }, isCancelled = { cancelled.get() }) }
        assertEquals(3, reads)
        assertPreviousIntact()
    }

    /** Provider whose read blocks until the stream is closed (a stalled cloud document). */
    private class Blocking : InputStream() {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            entered.countDown()
            released.await()
            throw IOException("stream closed")
        }
        override fun close() { released.countDown() }
    }

    @Test(timeout = 5_000) fun cancelReleasesAReadBlockedInsideTheProvider() {
        seed()
        val cancelled = AtomicBoolean(false)
        val src = Blocking()
        var error: Throwable? = null
        val worker = Thread { error = runCatching { import({ src }, isCancelled = { cancelled.get() }) }.exceptionOrNull() }
        worker.start()
        assertTrue(src.entered.await(2, TimeUnit.SECONDS))
        cancelled.set(true)
        worker.join(2_000)
        assertFalse("blocked read was not released by Cancel", worker.isAlive)
        assertTrue(error is InterruptedException)
        assertPreviousIntact()
        // The destination is free again: a new import of the same name is accepted.
        import({ "new\n".byteInputStream() })
        assertArrayEquals("new\n".toByteArray(), File(dir, "list.txt").readBytes())
    }

    @Test(timeout = 5_000) fun totalDeadlineStopsAStalledProvider() {
        seed()
        val now = java.util.concurrent.atomic.AtomicLong(0)
        val src = Blocking()
        var error: Throwable? = null
        val worker = Thread {
            error = runCatching {
                WordlistStore.importInto(dir, "list.txt", { false }, totalMillis = 1_000, clock = { now.get() }, open = { src })
            }.exceptionOrNull()
        }
        worker.start()
        assertTrue(src.entered.await(2, TimeUnit.SECONDS))
        now.set(TimeUnit.SECONDS.toNanos(2))
        worker.join(2_000)
        assertFalse("stalled provider outlived the deadline", worker.isAlive)
        assertTrue(error is app.brucia.wordlist.TransferTimeoutException)
        assertPreviousIntact()
    }

    @Test fun successfulImportReplacesAtomicallyAndCountsLines() {
        seed()
        val info = import({ "one\r\ntwo\n\nthree".byteInputStream() })
        assertEquals("list.txt", info.name)
        assertEquals(3, info.lines)
        assertEquals("one\r\ntwo\n\nthree", File(dir, "list.txt").readText())
        assertEquals(listOf("list.txt"), dir.listFiles()!!.map { it.name })
        assertEquals(info, WordlistStore.listIn(dir).single())
    }

    @Test fun listNeverShowsPartFilesEvenDuringImport() {
        seed()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val t = Thread {
            runCatching {
                import({
                    object : InputStream() {
                        var step = 0
                        override fun read(): Int = throw UnsupportedOperationException()
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (step++ == 1) { inside.countDown(); release.await() }
                            if (step > 2) throw IOException("provider interrupted")
                            b[off] = 'z'.code.toByte(); b[off + 1] = '\n'.code.toByte(); return 2
                        }
                    }
                })
            }
        }
        t.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".part") })
        assertEquals(listOf("list.txt"), WordlistStore.listIn(dir).map { it.name })
        release.countDown()
        t.join(5000)
        assertPreviousIntact()
        // A user file named like a staging file is stored under a listable name.
        assertEquals("x.part.txt", WordlistStore.sanitize("x.part"))
        assertEquals("wordlist.txt", WordlistStore.sanitize(".."))
    }

    @Test fun sameDestinationImportAndDownloadCannotInterleave() {
        seed()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Thread {
            runCatching {
                import({
                    object : InputStream() {
                        var step = 0
                        override fun read(): Int = throw UnsupportedOperationException()
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (step++ == 0) { inside.countDown(); release.await() }
                            if (step > 2) return -1
                            b[off] = 'n'.code.toByte(); b[off + 1] = '\n'.code.toByte(); return 2
                        }
                    }
                })
            }
        }
        first.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        // A second import of the same name fails before opening its source.
        var opened = false
        assertThrows(WordlistBusyException::class.java) { import({ opened = true; "x\n".byteInputStream() }) }
        assertFalse(opened)
        // A download of the same destination (WordlistStore.download path) is refused too.
        var downloaded = false
        assertThrows(WordlistBusyException::class.java) {
            WordlistStore.exclusive(File(dir, "list.txt")) { downloaded = true }
        }
        assertFalse(downloaded)
        // A different name is not blocked.
        assertEquals(1, WordlistStore.importInto(dir, "other.txt", { false }) { "o\n".byteInputStream() }.lines)
        release.countDown()
        first.join(5000)
        assertEquals("n\nn\n", File(dir, "list.txt").readText())
        // The lock is released after completion and after failure.
        WordlistStore.exclusive(File(dir, "list.txt")) { }
        assertThrows(IOException::class.java) { import({ FailingAfter("q".toByteArray()) }) }
        WordlistStore.exclusive(File(dir, "list.txt")) { }
    }

    @Test fun infoCountsHugeSingleLineInBoundedMemory() {
        val f = File(dir, "legacy.txt")
        // 48 MiB, no newline: useLines would build one 48M-char string.
        FileOutputStream(f).use { out -> val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }; repeat(48) { out.write(chunk) } }
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = mx.getThreadAllocatedBytes(Thread.currentThread().id)
        val info = WordlistStore.info(f)
        val allocated = mx.getThreadAllocatedBytes(Thread.currentThread().id) - before
        assertTrue("info allocated $allocated bytes", allocated < 8L * 1024 * 1024)
        assertEquals(1, info.lines)
        assertEquals(48L * 1024 * 1024, info.sizeBytes)
        f.writeText("a\r\nb\rc\n\n\nd")
        assertEquals(4, WordlistStore.info(f).lines)
    }
}
