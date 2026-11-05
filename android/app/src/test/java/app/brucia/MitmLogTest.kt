package app.brucia

import app.brucia.mitm.MitmLog
import app.brucia.mitm.MitmTxn
import app.brucia.mitm.RequestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The MITM request list keys its rows by [MitmTxn.id], and a LazyColumn crashes on a duplicate
 * key: every log entry must get its own id, and one proxied request must add one entry at most.
 */
class MitmLogTest {
    private fun txn(path: String, status: Int = 200) = MitmTxn(
        timeMs = 0, method = "GET", scheme = "http", host = "example.test", path = path,
        status = status, reqBytes = 0, respBytes = 0, durationMs = 0, decrypted = true, note = "",
        reqHeaders = "", respHeaders = "", reqPreview = "", respPreview = "",
    )

    private fun failed(e: Exception) = txn("/", status = -1).copy(note = "Upstream error: ${e.message}")

    private fun brokenPreview(): String = throw IOException("connection reset")

    @Before fun clean() = MitmLog.clear()
    @After fun cleanUp() = MitmLog.clear()

    private fun ids() = MitmLog.txns.value.map { it.id }

    @Test fun addAssignsAFreshIdToEveryEntryEvenForTheSameValue() {
        val draft = txn("/a").copy(id = 7)
        val first = MitmLog.add(draft)
        val second = MitmLog.add(draft)
        assertEquals(first.id + 1, second.id)
        assertEquals(listOf(second.id, first.id), ids()) // newest first
    }

    @Test fun idsKeepCountingAfterClear() {
        val before = MitmLog.add(txn("/a")).id
        MitmLog.clear()
        assertTrue(MitmLog.add(txn("/b")).id > before)
    }

    @Test fun failureAfterTheEntryWasLoggedAddsNoSecondEntry() {
        // The exchange logged the transaction, then closing the upstream socket threw: before,
        // the catch logged an error entry under the same, already used id.
        val keepAlive = RequestLog().run(failed = ::failed, onFailure = false) { log ->
            log.log(txn("/ok"))
            throw IOException("close_notify failed")
        }
        assertFalse(keepAlive)
        assertEquals(listOf("/ok"), MitmLog.txns.value.map { it.path })
        assertEquals(200, MitmLog.txns.value.single().status)
    }

    @Test fun failureBeforeTheEntryLogsOneErrorEntryWithTheNextId() {
        val previous = MitmLog.add(txn("/before")).id
        val keepAlive = RequestLog().run(failed = ::failed, onFailure = false) { log ->
            log.log(txn("/never").copy(reqPreview = brokenPreview())) // building the entry throws
            true
        }
        assertFalse(keepAlive)
        val entry = MitmLog.txns.value.first()
        assertEquals(-1, entry.status)
        assertEquals("Upstream error: connection reset", entry.note)
        assertEquals(previous + 1, entry.id) // no id was used up by the entry that was never added
        assertEquals(2, ids().toSet().size)
    }

    @Test fun successLogsOnceAndReturnsTheBlockResult() {
        val keepAlive = RequestLog().run(failed = ::failed, onFailure = false) { log ->
            log.log(txn("/ok"))
            log.log(txn("/again")) // a second call for the same request is ignored
            true
        }
        assertTrue(keepAlive)
        assertEquals(listOf("/ok"), MitmLog.txns.value.map { it.path })
    }

    @Test fun manyRequestsFromManyThreadsNeverShareAnId() {
        val threads = (0 until 8).map { n ->
            Thread {
                repeat(30) { i ->
                    RequestLog().run(failed = ::failed, onFailure = false) { log ->
                        log.log(txn("/$n/$i"))
                        if (i % 3 == 0) throw IOException("late failure")
                        true
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val all = ids()
        assertEquals(240, all.size)
        assertEquals(all.size, all.toSet().size)
    }
}
