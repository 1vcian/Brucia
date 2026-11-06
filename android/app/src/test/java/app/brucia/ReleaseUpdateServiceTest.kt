package app.brucia

import app.brucia.update.AppUpdater
import app.brucia.update.ReleaseCatalog
import app.brucia.update.ReleaseUpdateService
import app.brucia.update.ReleaseUpdateState
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReleaseUpdateServiceTest {
    private fun body(tag: String, vararg names: String): String = JSONObject()
        .put("tag_name", tag).put("body", "Notes $tag")
        .put("assets", JSONArray(names.map { name ->
            JSONObject().put("name", name).put("browser_download_url",
                "${BrandLinks.DOWNLOAD_PREFIX}$tag/$name")
        })).toString()

    private fun full(tag: String) = body(tag, "Brucia.apk", "Bruce-lilygo-t-embed-cc1101.bin", "Bruce-m5stack-cardputer.bin")
    private fun parsed(tag: String) = ReleaseCatalog.parse(full(tag))

    private fun assertUnavailable(state: ReleaseUpdateState) {
        assertNull(state.release)
        assertEquals("", state.latest)
        assertEquals("", state.notes)
        assertFalse(state.newer)
        assertNull(state.apkUrl)
        assertNull(state.firmwareUrl)
        assertNull(state.flashUrl)
        assertNull(state.selectedBoard)
        assertTrue(state.boards.isEmpty())
    }

    @Test(timeout = 5000) fun changingApiResponsesNeverMixMetadataAndAssets() = runVirtual {
        val responses = ArrayDeque(listOf(full("v0.2"), full("v0.3")))
        var calls = 0
        val service = ReleaseUpdateService(this, fetch = {
            AppUpdater.fetchSnapshot { url ->
                assertEquals(AppUpdater.RELEASES_API, url)
                calls++
                responses.removeFirst()
            }
        }, dispatcher = virtualUnconfined)
        service.refresh("0.1")!!.join()
        val first = service.state.value
        assertEquals(1, calls)
        assertEquals("v0.2", first.latest)
        assertTrue(first.newer)
        assertTrue(service.select(first.boards.first()))
        service.refresh("0.2")!!.join()
        val second = service.state.value
        assertEquals(2, calls)
        assertEquals("v0.3", second.latest)
        assertEquals("Notes v0.3", second.notes)
        assertTrue(second.apkUrl!!.contains("/v0.3/"))
        assertTrue(second.boards.all { it.url.contains("/v0.3/") })
        assertEquals(second.boards.first().url, second.flashUrl)
        assertNull(second.selectedBoard)
        assertFalse(service.select(first.boards.first()))
        // Previously captured snapshots also remain internally coherent.
        assertEquals("v0.2", first.latest)
        assertTrue(first.boards.all { it.url.contains("/v0.2/") })
    }

    @Test(timeout = 5000) fun failedRefreshClearsSelectionAndEveryActionableUrlAndCanRetry() = runVirtual {
        var attempt = 0
        val service = ReleaseUpdateService(this, fetch = {
            when (++attempt) { 2 -> throw IOException("HTTP 404"); else -> parsed("v0.2") }
        }, dispatcher = virtualUnconfined)
        service.refresh("0.1")!!.join()
        val board = service.state.value.boards.first()
        assertTrue(service.select(board))
        service.refresh("0.1")!!.join()
        assertUnavailable(service.state.value)
        assertEquals("HTTP 404", service.state.value.error)
        assertFalse(service.state.value.checking)
        assertFalse(service.select(board))
        service.refresh("0.1")!!.join()
        assertEquals("v0.2", service.state.value.latest)
        assertNull(service.state.value.error)
        assertNull(service.state.value.selectedBoard)
    }

    @Test(timeout = 5000) fun refreshInvalidatesBeforeIoAndDuplicateCallDoesNotFetchAgain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        var calls = 0
        val service = ReleaseUpdateService(this, fetch = {
            if (++calls == 2) {
                entered.complete(Unit)
                check(gate.await(2, TimeUnit.SECONDS))
            }
            parsed("v0.2")
        })
        service.refresh("0.1")!!.join()
        val board = service.state.value.boards.first()
        service.select(board)
        val job = service.refresh("0.1")!!
        try {
            assertTrue(service.state.value.checking)
            assertUnavailable(service.state.value)
            entered.await()
            assertNull(service.refresh("9.9"))
            assertFalse(service.select(board))
            assertFalse(service.select(null))
            assertEquals("0.1", service.state.value.current)
            assertEquals(2, calls)
        } finally { gate.countDown() }
        job.join()
        assertNotNull(service.state.value.apkUrl)
    }

    @Test(timeout = 5000) fun explicitUnsupportedOrForeignBoardNeverFallsBackToDefaultImage() = runVirtual {
        val service = ReleaseUpdateService(this, { parsed("v0.2") }, virtualUnconfined)
        service.refresh("0.1")!!.join()
        val auto = service.state.value.flashUrl
        val cardputer = service.state.value.boards.last()
        assertTrue(service.select(cardputer))
        assertNull(service.state.value.flashUrl)
        assertFalse(service.select(cardputer.copy(url = "https://example.org/unrelated.bin")))
        assertEquals(cardputer, service.state.value.selectedBoard)
        assertTrue(service.select(null))
        assertEquals(auto, service.state.value.flashUrl)
        assertTrue(service.select(service.state.value.boards.first()))
        assertEquals(auto, service.state.value.flashUrl)
    }

    @Test(timeout = 5000) fun missingAssetsInNextReleaseDoNotKeepPreviousDownloads() = runVirtual {
        var response = full("v0.2")
        val service = ReleaseUpdateService(this, { AppUpdater.fetchSnapshot { response } }, virtualUnconfined)
        service.refresh("0.1")!!.join()
        response = body("v0.3", "Bruce-m5stack-cardputer.bin")
        service.refresh("0.1")!!.join()
        val current = service.state.value
        assertEquals("v0.3", current.latest)
        assertNull(current.apkUrl)
        assertNull(current.firmwareUrl)
        assertNull(current.flashUrl)
        assertEquals(1, current.boards.size)
        assertTrue(service.select(current.boards.single()))
        assertNull(service.state.value.flashUrl)
    }

    @Test(timeout = 5000) fun malformedOrAmbiguousRefreshFailsAsOneUnit() = runVirtual {
        var response = full("v0.2")
        val service = ReleaseUpdateService(this, { AppUpdater.fetchSnapshot { response } }, virtualUnconfined)
        service.refresh("0.1")!!.join()
        for (invalid in listOf("not json", body("v0.3", "one.apk", "two.apk"))) {
            response = invalid
            service.refresh("0.1")!!.join()
            assertUnavailable(service.state.value)
            assertNotNull(service.state.value.error)
            assertFalse(service.state.value.checking)
        }
    }

    @Test(timeout = 5000) fun cancelledBlockingFetchCannotPublishLateResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val service = ReleaseUpdateService(this, {
            entered.complete(Unit)
            check(gate.await(2, TimeUnit.SECONDS))
            parsed("v0.2")
        })
        val job = service.refresh("0.1")!!
        try {
            entered.await()
            job.cancel()
        } finally { gate.countDown() }
        job.join()
        assertUnavailable(service.state.value)
        assertFalse(service.state.value.checking)
        assertEquals("Check cancelled", service.state.value.error)
    }

    @Test(timeout = 5000) fun alreadyCancelledParentDoesNotLeaveCheckingStuck() = runVirtual {
        val parent = Job().apply { cancel() }
        var fetched = false
        val service = ReleaseUpdateService(CoroutineScope(parent + virtualUnconfined), {
            fetched = true
            parsed("v0.2")
        })
        service.refresh("0.1")!!.join()
        assertFalse(fetched)
        assertUnavailable(service.state.value)
        assertFalse(service.state.value.checking)
    }

    @Test fun parsedBoardListCannotBeMutatedAfterPublication() {
        val snapshot = parsed("v0.2")
        try {
            (snapshot.boards as MutableList).clear()
            fail("Mutable release assets")
        } catch (_: UnsupportedOperationException) { }
        assertEquals(2, snapshot.boards.size)
    }
}
