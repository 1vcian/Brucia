package app.brucia

import app.brucia.nfc.MifareDictionary
import app.brucia.nfc.MifareDictionaryService
import app.brucia.wordlist.DictionaryDownload
import app.brucia.wordlist.TransferTimeoutException
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** F41: Cancel and the total time limit must end blocked dictionary I/O, not only fence its result. */
class DictionaryTransferTest {
    /** Counts threads inside read(); the gauge that must return to 0 after Cancel. */
    private class Readers {
        val inRead = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        fun enter() { started.incrementAndGet(); peak.accumulateAndGet(inRead.incrementAndGet(), ::maxOf) }
        fun leave() { inRead.decrementAndGet() }
    }

    /**
     * A body whose read blocks until the connection is disconnected (as a socket read does
     * when another thread closes the socket), then fails after [unwindMs]. Never returns data.
     */
    private class StalledConnection(private val readers: Readers, private val unwindMs: Long = 0) :
        HttpURLConnection(URL("https://example.com/list")) {
        val gone = CountDownLatch(1)
        override fun disconnect() = gone.countDown()
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read() = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readers.enter()
                try {
                    // Safety cap so a regression fails the assertions instead of hanging the suite.
                    if (gone.await(5, TimeUnit.SECONDS)) {
                        if (unwindMs > 0) Thread.sleep(unwindMs)
                        throw SocketException("Socket closed")
                    }
                    return -1
                } finally { readers.leave() }
            }
        }
        override fun getContentLengthLong() = -1L
        override fun getResponseCode() = 200
        override fun usingProxy() = false
        override fun connect() {}
    }

    /** Returns one byte per read and advances [clock] by [stepNanos]: a trickling server. */
    private class TrickleConnection(private val clock: AtomicLong, private val stepNanos: Long) :
        HttpURLConnection(URL("https://example.com/list")) {
        var closed = false
        val reads = AtomicInteger()
        override fun disconnect() { closed = true }
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read() = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads.incrementAndGet()
                clock.addAndGet(stepNanos)
                b[off] = 'a'.code.toByte()
                return 1
            }
        }
        override fun getContentLengthLong() = 1000L
        override fun getResponseCode() = 200
        override fun usingProxy() = false
        override fun connect() {}
    }

    private class OkConnection(val body: ByteArray) : HttpURLConnection(URL("https://example.com/list")) {
        override fun getInputStream() = body.inputStream()
        override fun getContentLengthLong() = body.size.toLong()
        override fun getResponseCode() = 200
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun connect() {}
    }

    private fun waitUntil(ms: Long = 2000, what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (!condition()) {
            if (System.nanoTime() > end) fail("Timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    private fun <T> background(block: () -> T): Pair<Thread, AtomicReference<Result<T>?>> {
        val result = AtomicReference<Result<T>?>(null)
        val thread = Thread { result.set(runCatching(block)) }.apply { isDaemon = true; start() }
        return thread to result
    }

    @Test fun cancelDisconnectsABlockedReadPromptly() {
        val readers = Readers()
        val cancel = AtomicBoolean()
        val (thread, result) = background {
            DictionaryDownload.read("https://example.com/list", 100, { cancel.get() }, { StalledConnection(readers) })
        }
        waitUntil(what = "reader blocked") { readers.inRead.get() == 1 }
        val cancelledAt = System.nanoTime()
        cancel.set(true)
        waitUntil(1000, "reader released after Cancel") { readers.inRead.get() == 0 }
        thread.join(1000)
        assertTrue("Cancel took too long", System.nanoTime() - cancelledAt < TimeUnit.MILLISECONDS.toNanos(1000))
        assertTrue(result.get()!!.exceptionOrNull() is InterruptedException)
    }

    @Test fun totalDeadlineStopsAStalledStoreAndKeepsPreviousList() {
        val dir = Files.createTempDirectory("dictionary-transfer").toFile()
        try {
            val dest = File(dir, "list.txt").apply { writeText("previous-password\n") }
            val readers = Readers()
            val clock = AtomicLong(0)
            val (thread, result) = background {
                DictionaryDownload.store(dest, "https://example.com/list", 1000, { false }, { _, _ -> },
                    { StalledConnection(readers) }, totalMillis = 60_000, clock = clock::get)
            }
            waitUntil(what = "reader blocked") { readers.inRead.get() == 1 }
            clock.addAndGet(TimeUnit.SECONDS.toNanos(61)) // Past the total limit; no data arrived.
            waitUntil(1000, "reader released at deadline") { readers.inRead.get() == 0 }
            thread.join(1000)
            assertTrue(result.get()!!.exceptionOrNull() is TransferTimeoutException)
            assertEquals("previous-password\n", dest.readText())
            assertEquals(listOf("list.txt"), dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }

    @Test fun totalDeadlineStopsATrickleThatNeverHitsTheReadTimeout() {
        val clock = AtomicLong(0)
        val conn = TrickleConnection(clock, TimeUnit.SECONDS.toNanos(1)) // 1 byte per second
        val error = assertThrows(TransferTimeoutException::class.java) {
            DictionaryDownload.read("https://example.com/list", 1000, connect = { conn },
                totalMillis = 10_000, clock = clock::get)
        }
        assertTrue(error.message!!.contains("10 s"))
        assertTrue("trickle not stopped near the limit: ${conn.reads.get()} reads", conn.reads.get() in 10..11)
        assertTrue(conn.closed)
    }

    @Test fun repeatedCancelAndRetryNeverRunsTwoReadersAndKeepsKeys() {
        val pool = Executors.newFixedThreadPool(4)
        val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())
        try {
            val service = MifareDictionaryService(scope, pool.asCoroutineDispatcher())
            service.load("seed") { MifareDictionary.read("A0A1A2A3A4A5\n".byteInputStream(), it) }
            waitUntil(what = "seed keys") { service.status.value.keys == listOf("A0A1A2A3A4A5") }

            val readers = Readers()
            repeat(5) { round ->
                // Each stalled reader needs 150 ms to unwind after disconnect, so an overlapping
                // restart would be visible as a second reader.
                service.load("round $round") { cancelled ->
                    MifareDictionary.parse(String(DictionaryDownload.read("https://example.com/list", 100, cancelled,
                        { StalledConnection(readers, unwindMs = 150) })))
                }
                waitUntil(what = "round $round reader blocked") { readers.started.get() == round + 1 }
                service.cancel()
                assertFalse(service.status.value.busy)
            }
            waitUntil(1500, "all readers released") { readers.inRead.get() == 0 }
            assertEquals(5, readers.started.get())
            assertEquals("a restart overlapped a cancelled reader", 1, readers.peak.get())
            assertEquals(listOf("A0A1A2A3A4A5"), service.status.value.keys)

            // Retry after cancel works and merges with the previous keys.
            service.load("retry") { cancelled ->
                MifareDictionary.parse(String(DictionaryDownload.read("https://example.com/list", 100, cancelled,
                    { OkConnection("FFFFFFFFFFFF\n".toByteArray()) })))
            }
            waitUntil(what = "retry merged") { service.status.value.keys.size == 2 && !service.status.value.busy }
            assertEquals(1, readers.peak.get())
        } finally {
            scope.cancel()
            pool.shutdownNow()
        }
    }

    @Test fun restartWaitsForTheCancelledReaderBeforeReading() {
        val pool = Executors.newFixedThreadPool(4)
        val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())
        try {
            val service = MifareDictionaryService(scope, pool.asCoroutineDispatcher())
            val readers = Readers()
            service.load("A") { cancelled ->
                MifareDictionary.parse(String(DictionaryDownload.read("https://example.com/list", 100, cancelled,
                    { StalledConnection(readers, unwindMs = 300) })))
            }
            waitUntil(what = "A blocked") { readers.inRead.get() == 1 }
            service.cancel()
            val bStarted = AtomicBoolean()
            service.load("B") { bStarted.set(true); assertEquals(0, readers.inRead.get()); listOf("FFFFFFFFFFFF") }
            waitUntil(what = "B done") { service.status.value.keys == listOf("FFFFFFFFFFFF") }
            assertTrue(bStarted.get())
            assertEquals(1, readers.peak.get())
        } finally {
            scope.cancel()
            pool.shutdownNow()
        }
    }

    @Test fun providerImportCancelAndDeadlineCloseTheStream() {
        // A provider stream that blocks until closed (pipe / remote document provider).
        class StalledStream(val readers: Readers) : InputStream() {
            val closed = CountDownLatch(1)
            override fun read() = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readers.enter()
                try {
                    if (closed.await(5, TimeUnit.SECONDS)) throw java.io.IOException("Stream closed")
                    return -1
                } finally { readers.leave() }
            }
            override fun close() = closed.countDown()
        }
        val readers = Readers()
        val cancel = AtomicBoolean()
        val (t1, r1) = background { MifareDictionary.read(StalledStream(readers), { cancel.get() }) }
        waitUntil(what = "import blocked") { readers.inRead.get() == 1 }
        cancel.set(true)
        waitUntil(1000, "import released after Cancel") { readers.inRead.get() == 0 }
        t1.join(1000)
        assertTrue(r1.get()!!.exceptionOrNull() is InterruptedException)

        val clock = AtomicLong(0)
        val (t2, r2) = background {
            MifareDictionary.read(StalledStream(readers), totalMillis = 1000, clock = clock::get)
        }
        waitUntil(what = "import blocked") { readers.inRead.get() == 1 }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2))
        waitUntil(1000, "import released at deadline") { readers.inRead.get() == 0 }
        t2.join(1000)
        assertTrue(r2.get()!!.exceptionOrNull() is TransferTimeoutException)
    }
}
