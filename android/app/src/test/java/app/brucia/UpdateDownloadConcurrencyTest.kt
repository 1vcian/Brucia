package app.brucia

import app.brucia.transport.FlashPortOwner
import app.brucia.transport.PortLease
import app.brucia.transport.launchFlashOwned
import app.brucia.update.AppDownloadService
import app.brucia.update.AssetIntegrity
import app.brucia.update.DownloadBusyException
import app.brucia.update.DownloadIO
import app.brucia.update.IntegrityException
import app.brucia.update.ReleaseCatalog
import app.brucia.update.ReleaseUpdateService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency and cancellation of the update download path: fake HTTP connections and stepped
 * streams whose read hooks trigger the race deterministically (no sleeps, no real network).
 */
class UpdateDownloadConcurrencyTest {
    private val dir: File = Files.createTempDirectory("brucia-update-conc").toFile()
    private val apk get() = File(dir, "brucia-update.apk")

    @After fun cleanup() { dir.deleteRecursively() }

    /** Delivers [data] in [chunk]-byte reads; [onRead] runs before each read with the bytes delivered so far. */
    private class StepStream(val data: ByteArray, val chunk: Int, val onRead: (Int) -> Unit = {}) : InputStream() {
        @Volatile var pos = 0
        val reads = AtomicInteger()
        @Volatile var closed = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            onRead(pos); reads.incrementAndGet()
            if (pos >= data.size) return -1
            val n = minOf(chunk, len, data.size - pos)
            System.arraycopy(data, pos, b, off, n); pos += n
            return n
        }
        override fun close() { closed = true }
    }

    private class FakeConn(url: String, private val code: Int, private val body: InputStream, private val length: Long) :
        HttpURLConnection(URL(url)) {
        val disconnects = AtomicInteger()
        override fun getResponseCode() = code
        override fun getContentLengthLong() = length
        override fun getInputStream() = body
        override fun disconnect() { disconnects.incrementAndGet() }
        override fun usingProxy() = false
        override fun connect() { }
    }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it * 31 + seed).toByte() }
    private fun integrityOf(data: ByteArray) = AssetIntegrity(data.size.toLong(), AssetIntegrity.hex(AssetIntegrity.sha256Of(data)))
    private fun leftovers() = dir.listFiles().orEmpty().map { it.name }.sorted()

    private fun downloadFile(conn: () -> HttpURLConnection, integrity: AssetIntegrity, isCancelled: () -> Boolean = { false },
                             onProgress: (Long, Long) -> Unit = { _, _ -> },
                             sink: (File) -> OutputStream = { FileOutputStream(it) }): File =
        DownloadIO.downloadFile(dir, "brucia-update.apk", ReleaseCatalog.APK_MAX_BYTES, integrity, conn, isCancelled, onProgress, sink)

    // --- File download (APK) ------------------------------------------------------------------

    @Test(timeout = 10_000) fun concurrentDownloadsOfOneDestinationRunExactlyOnce() {
        val first = bytes(300_000, 1)
        val second = bytes(200_000, 2)
        val firstReading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstStream = StepStream(first, 65_536) { pos ->
            if (pos > 0 && firstReading.count > 0) { firstReading.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        var firstResult: Any? = null
        val worker = Thread {
            firstResult = try { downloadFile({ FakeConn("https://a/1", 200, firstStream, first.size.toLong()) }, integrityOf(first)) }
                catch (e: Throwable) { e }
        }
        worker.start()
        assertTrue(firstReading.await(5, TimeUnit.SECONDS))
        var secondOpened = false
        try {
            downloadFile({ secondOpened = true; FakeConn("https://a/2", 200, StepStream(second, 65_536), second.size.toLong()) }, integrityOf(second))
            fail("Second concurrent download of the same destination was not refused")
        } catch (_: DownloadBusyException) { }
        assertFalse("Refused request must not open a connection", secondOpened)
        release.countDown()
        worker.join(5_000)
        val result = firstResult
        assertTrue("first download failed: $result", result is File)
        assertArrayEquals(first, (result as File).readBytes())
        assertEquals(listOf("brucia-update.apk"), leftovers())
        // The gate is released after the first one ends: a later download is accepted.
        assertArrayEquals(second, downloadFile({ FakeConn("https://a/2", 200, StepStream(second, 65_536), second.size.toLong()) },
            integrityOf(second)).readBytes())
    }

    @Test(timeout = 10_000) fun cancellationMidDownloadStopsReadingAndRemovesPartialAndPreviousFile() {
        apk.writeText("old verified apk")
        val data = bytes(400_000, 3)
        var cancelled = false
        val stream = StepStream(data, 65_536) { pos -> if (pos >= 2 * 65_536) cancelled = true }
        val conn = FakeConn("https://a", 200, stream, data.size.toLong())
        try { downloadFile({ conn }, integrityOf(data), { cancelled }); fail("Cancellation ignored") }
        catch (_: InterruptedException) { }
        assertEquals("No read after the cancelling one", 3, stream.reads.get())
        assertTrue(stream.closed)
        assertEquals(1, conn.disconnects.get())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test(timeout = 10_000) fun networkErrorAfterPartialDataLeavesNoFile() {
        apk.writeText("old verified apk")
        val data = bytes(400_000, 4)
        val stream = StepStream(data, 65_536) { pos -> if (pos >= 3 * 65_536) throw IOException("connection reset") }
        val conn = FakeConn("https://a", 200, stream, data.size.toLong())
        try { downloadFile({ conn }, integrityOf(data)); fail("Partial download accepted") }
        catch (e: IOException) { assertEquals("connection reset", e.message) }
        assertTrue(stream.closed)
        assertEquals(1, conn.disconnects.get())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test(timeout = 10_000) fun integrityFailuresLeaveNoInstallableFile() {
        val data = bytes(100_000, 5)
        val wrongDigest = AssetIntegrity(data.size.toLong(), AssetIntegrity.hex(AssetIntegrity.sha256Of(bytes(100_000, 6))))
        val wrongDeclaredSize = AssetIntegrity(data.size + 1L, null)
        for (integrity in listOf(wrongDigest, wrongDeclaredSize)) {
            apk.writeText("old verified apk")
            try { downloadFile({ FakeConn("https://a", 200, StepStream(data, 65_536), data.size.toLong()) }, integrity); fail("Mismatch accepted") }
            catch (_: IntegrityException) { }
            assertEquals(emptyList<String>(), leftovers())
        }
    }

    @Test(timeout = 10_000) fun diskFullDuringWriteLeavesNoFile() {
        apk.writeText("old verified apk")
        val data = bytes(300_000, 7)
        val full = { part: File ->
            object : OutputStream() {
                val file = FileOutputStream(part)
                var written = 0
                override fun write(b: Int) = throw UnsupportedOperationException()
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written + len > 100_000) throw IOException("No space left on device")
                    file.write(b, off, len); written += len
                }
                override fun close() = file.close()
            }
        }
        try { downloadFile({ FakeConn("https://a", 200, StepStream(data, 65_536), data.size.toLong()) }, integrityOf(data), sink = full); fail() }
        catch (e: IOException) { assertEquals("No space left on device", e.message) }
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test(timeout = 10_000) fun promoteFailureLeavesNoPartialOrInstallableFile() {
        val data = bytes(100_000, 8)
        // Something occupies the destination after it was cleared: the final move must fail.
        val stream = StepStream(data, 65_536) { pos ->
            if (pos == data.size && !apk.exists()) { apk.mkdirs(); File(apk, "occupied").writeText("x") }
        }
        try { downloadFile({ FakeConn("https://a", 200, stream, data.size.toLong()) }, integrityOf(data)); fail("Promote failure ignored") }
        catch (_: IOException) { }
        assertFalse(apk.isFile)
        assertEquals(listOf("brucia-update.apk"), leftovers()) // only the blocking directory, no .part
    }

    @Test(timeout = 10_000) fun undeletablePreviousFileAndHttpErrorsNeverReachInstall() {
        File(apk, "x").also { apk.mkdirs() }.writeText("x")
        var opened = false
        try { downloadFile({ opened = true; FakeConn("https://a", 200, StepStream(byteArrayOf(1), 1), 1) }, AssetIntegrity.NONE); fail() }
        catch (e: IOException) { assertEquals("Cannot clear previous update file", e.message) }
        assertFalse("No network before the previous file is gone", opened)
        apk.deleteRecursively()
        apk.writeText("old verified apk")
        val conn = FakeConn("https://a", 500, StepStream(byteArrayOf(1), 1), 1)
        try { downloadFile({ conn }, AssetIntegrity.NONE); fail() } catch (e: IOException) { assertEquals("HTTP 500", e.message) }
        assertEquals(1, conn.disconnects.get())
        assertEquals(emptyList<String>(), leftovers())
    }

    // --- AppDownloadService (ViewModel downloadAndInstallApp) -------------------------------

    private class Installs { val files = Collections.synchronizedList(mutableListOf<ByteArray>()) }

    /** Same shape as the ViewModel: real file download, installer behind a dispatcher switch. */
    private fun service(scope: CoroutineScope, installs: Installs, connect: (String) -> HttpURLConnection,
                        dispatcher: CoroutineDispatcher = Dispatchers.IO) =
        AppDownloadService(scope, download = { url, integrity, progress, cancelled ->
            downloadFile({ connect(url) }, integrity, cancelled, progress)
        }, install = { apk -> withContext(Dispatchers.Unconfined) { installs.files += apk.readBytes() } }, dispatcher = dispatcher)

    @Test(timeout = 10_000) fun doubleTapStartsExactlyOneDownloadAndOneInstall() = runBlocking {
        val data = bytes(200_000, 9)
        val gate = CountDownLatch(1)
        val connects = AtomicInteger()
        val installs = Installs()
        val service = service(this, installs, { url ->
            connects.incrementAndGet(); gate.await(5, TimeUnit.SECONDS)
            FakeConn(url, 200, StepStream(data, 65_536), data.size.toLong())
        })
        val job = service.start("https://a/v0.2/Brucia.apk", integrityOf(data))!!
        assertNull("Second tap must be refused while the first runs", service.start("https://a/v0.2/Brucia.apk", integrityOf(data)))
        assertTrue(service.state.value.downloading)
        gate.countDown(); job.join()
        assertEquals(1, connects.get())
        assertEquals(1, installs.files.size)
        assertArrayEquals(data, installs.files.single())
        assertFalse(service.state.value.downloading)
        assertNull(service.state.value.error)
    }

    private fun releaseBody(tag: String, apk: ByteArray): String = JSONObject().put("tag_name", tag).put("assets", JSONArray(listOf(
        JSONObject().put("name", "Brucia.apk").put("size", apk.size)
            .put("digest", "sha256:" + AssetIntegrity.hex(AssetIntegrity.sha256Of(apk)))
            .put("browser_download_url", "https://github.com/1vcian/BruciaDev/releases/download/$tag/Brucia.apk")))).toString()

    @Test(timeout = 10_000) fun refreshDuringDownloadDoesNotSwapUrlOrIntegrity() = runBlocking {
        val v2 = bytes(150_000, 10)
        val v3 = bytes(170_000, 11)
        val responses = ArrayDeque(listOf(releaseBody("v0.2", v2), releaseBody("v0.3", v3)))
        val releases = ReleaseUpdateService(this, fetch = { ReleaseCatalog.parse(responses.removeFirst()) }, dispatcher = Dispatchers.Unconfined)
        releases.refresh("0.1")!!.join()
        val gate = CountDownLatch(1)
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val installs = Installs()
        val downloads = service(this, installs, { url ->
            requested += url; gate.await(5, TimeUnit.SECONDS)
            val body = if ("/v0.2/" in url) v2 else v3
            FakeConn(url, 200, StepStream(body, 65_536), body.size.toLong())
        })
        val snapshot = releases.state.value
        val job = downloads.start(snapshot.apkUrl!!, snapshot.apkIntegrity)!!
        releases.refresh("0.1")!!.join()
        assertTrue(releases.state.value.apkUrl!!.contains("/v0.3/"))
        gate.countDown(); job.join()
        assertEquals(listOf(snapshot.apkUrl), requested.toList())
        assertArrayEquals(v2, installs.files.single())
        assertNull(downloads.state.value.error)
    }

    @Test(timeout = 10_000) fun cancellingTheDownloadStopsReadsRemovesFilesAndClearsDownloading() = runBlocking {
        apk.writeText("old verified apk")
        val data = bytes(600_000, 12)
        val parent = Job()
        val scope = CoroutineScope(coroutineContext + parent)
        val stream = StepStream(data, 65_536) { pos -> if (pos >= 2 * 65_536) parent.cancel() }
        val installs = Installs()
        val service = service(scope, installs, { url -> FakeConn(url, 200, stream, data.size.toLong()) })
        service.start("https://a/Brucia.apk", integrityOf(data))!!.join()
        assertEquals("Reads must stop at the next chunk after cancellation", 3, stream.reads.get())
        assertEquals(0, installs.files.size)
        assertEquals(emptyList<String>(), leftovers())
        val state = service.state.value
        assertFalse(state.downloading)
        assertTrue(state.error.orEmpty(), state.error.orEmpty().contains("cancelled", ignoreCase = true))
    }

    @Test(timeout = 10_000) fun cancelledBeforeStartNeverLeavesDownloadingTrue() {
        val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
        var opened = false
        val service = service(scope, Installs(), { url -> opened = true; FakeConn(url, 200, StepStream(byteArrayOf(1), 1), 1) },
            dispatcher = Dispatchers.Unconfined)
        service.start("https://a/Brucia.apk", AssetIntegrity.NONE)
        assertFalse(opened)
        assertFalse("downloading stuck after a cancelled start", service.state.value.downloading)
        assertNotNull(service.state.value.error)
    }

    @Test(timeout = 10_000) fun failedNewDownloadNeverInstallsThePreviousApk() = runBlocking {
        val old = bytes(50_000, 13)
        val data = bytes(120_000, 14)
        val cases = listOf<Pair<(String) -> HttpURLConnection, String>>(
            { url: String -> FakeConn(url, 200, StepStream(data, 65_536) { if (it > 0) throw IOException("reset") }, data.size.toLong()) } to "reset",
            { url: String -> FakeConn(url, 200, StepStream(bytes(120_000, 15), 65_536), data.size.toLong()) } to "Nothing was installed.",
            { url: String -> FakeConn(url, 404, StepStream(data, 65_536), data.size.toLong()) } to "HTTP 404",
        )
        for ((connect, message) in cases) {
            apk.writeBytes(old)
            val installs = Installs()
            val service = service(this, installs, connect)
            service.start("https://a/Brucia.apk", integrityOf(data))!!.join()
            assertEquals(0, installs.files.size)
            assertEquals(emptyList<String>(), leftovers())
            assertFalse(service.state.value.downloading)
            assertTrue(service.state.value.error.orEmpty(), service.state.value.error.orEmpty().endsWith(message))
            // A later request is accepted after a failure.
            assertNotNull(service.start("https://a/Brucia.apk", AssetIntegrity.NONE)?.also { it.cancelAndJoin() })
        }
    }

    // --- Flash download step (ViewModel flashFirmware) -----------------------------------------

    private class FlashRun(val bytes: ByteArray?, val stopped: Boolean, val flashed: Boolean, val progressAfterEnd: Int)

    /**
     * The ViewModel's flash body up to the write: download bound to the token, then the write.
     * [end] runs from a read hook once 512 KiB arrived (abort, owner shutdown or job cancel).
     */
    private fun runFlashDownload(end: (FlashPortOwner, Long, Job) -> Unit): Pair<FlashRun, Int> = runBlocking {
        val owner = FlashPortOwner(clock = { 0L })
        val token = owner.begin(1_000)!!
        val leaseCloses = AtomicInteger()
        assertEquals(FlashPortOwner.Adopt.ADOPTED, owner.adopt(token, PortLease { leaseCloses.incrementAndGet() }))
        val image = bytes(1_048_576, 16)
        val parent = Job()
        var ended = false
        val stream = StepStream(image, 65_536) { pos -> if (pos >= 512 * 1024 && !ended) { ended = true; end(owner, token, parent) } }
        val conn = FakeConn("https://a/fw.bin", 200, stream, image.size.toLong())
        var run: FlashRun? = null
        var abandoned = false
        val job = CoroutineScope(parent).launchFlashOwned(owner, token, Dispatchers.IO, onAbandoned = { abandoned = true }) {
            val ours = { owner.flashToken == token }
            val owned = { ours() && isActive }
            var after = 0
            run = try {
                val bytes = DownloadIO.owned(owned, onProgress = { _, _ -> if (ended) after++ }) { progress, cancelled ->
                    DownloadIO.downloadBytes({ conn }, ReleaseCatalog.FIRMWARE_MAX_BYTES, integrityOf(image), cancelled, progress)
                }
                FlashRun(bytes, false, true, after)
            } catch (_: InterruptedException) { FlashRun(null, true, false, after) }
        }
        job.join()
        assertFalse(abandoned)
        assertNull(owner.flashToken)
        assertEquals("port released exactly once", 1, leaseCloses.get())
        assertEquals(1, conn.disconnects.get())
        assertTrue(stream.closed)
        run!! to stream.reads.get()
    }

    @Test(timeout = 10_000) fun flashAbortMidDownloadStopsReadsAndNeverReachesTheWrite() {
        val (run, reads) = runFlashDownload { owner, token, _ -> assertTrue(owner.finish(token)) }
        assertTrue(run.stopped); assertFalse(run.flashed); assertNull(run.bytes)
        assertEquals(0, run.progressAfterEnd)
        assertEquals(9, reads)
    }

    @Test(timeout = 10_000) fun ownerShutdownOrJobCancelStopsTheFlashDownload() {
        for (end in listOf<(FlashPortOwner, Long, Job) -> Unit>({ owner, _, _ -> owner.close() }, { _, _, job -> job.cancel() })) {
            val (run, reads) = runFlashDownload(end)
            assertTrue(run.stopped); assertFalse(run.flashed)
            assertEquals(0, run.progressAfterEnd)
            assertEquals(9, reads)
        }
    }

    @Test(timeout = 10_000) fun firmwareNetworkErrorAndMissingReleaseReturnNoBytes() {
        val image = bytes(300_000, 17)
        val stream = StepStream(image, 65_536) { if (it > 0) throw IOException("reset") }
        try { DownloadIO.downloadBytes({ FakeConn("https://a", 200, stream, image.size.toLong()) }, 1L shl 24, integrityOf(image), { false }, { _, _ -> }); fail() }
        catch (e: IOException) { assertEquals("reset", e.message) }
        try { DownloadIO.downloadBytes({ FakeConn("https://a", 404, stream, 1) }, 1L shl 24, AssetIntegrity.NONE, { false }, { _, _ -> }, "Release unavailable"); fail() }
        catch (e: IOException) { assertEquals("Release unavailable", e.message) }
    }
}
