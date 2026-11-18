package app.brucia

import app.brucia.wardrive.CmdStatus
import app.brucia.wardrive.CommandHistory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class CommandHistoryTest {
    @Test fun startsNewestFirstWithLocalIdsAndDispatchTime() {
        var now = 100L
        val history = CommandHistory { now }
        assertTrue(history.entries.value.isEmpty())
        val first = history.start("info")
        now = 120
        val second = history.start("SUB wifi.ap on")
        val entries = history.entries.value
        assertTrue(second > first)
        assertEquals(listOf(second, first), entries.map { it.id })
        assertEquals(listOf("SUB wifi.ap on", "info"), entries.map { it.text })
        assertEquals(listOf(120L, 100L), entries.map { it.sentAtMs })
        assertTrue(entries.all { it.status == CmdStatus.RUNNING && it.doneAtMs == null && it.result == null && it.ok == null })
    }

    @Test fun outOfOrderResultsResolveOnlyTheirOwnEntriesWithoutReordering() {
        var now = 100L
        val history = CommandHistory { now }
        val first = history.start("info")
        val second = history.start("free")
        val third = history.start("uptime")
        now = 200
        history.complete(second, false, "  device busy\n")
        now = 250
        history.complete(first, true, " board ")
        val entries = history.entries.value
        assertEquals(listOf(third, second, first), entries.map { it.id })
        assertEquals(CmdStatus.RUNNING, entries[0].status)
        assertEquals(CmdStatus.FAILED, entries[1].status)
        assertEquals(false, entries[1].ok)
        assertEquals("device busy", entries[1].result)
        assertEquals(200L, entries[1].doneAtMs)
        assertEquals(CmdStatus.DONE, entries[2].status)
        assertEquals(true, entries[2].ok)
        assertEquals("board", entries[2].result)
        assertEquals(250L, entries[2].doneAtMs)
        assertTrue(entries.all { it.sentAtMs == 100L })
    }

    @Test fun resultLimitIsAppliedAfterTrimmingWithEllipsisOnlyWhenNeeded() {
        val history = CommandHistory()
        for (length in listOf(0, 599, 600, 601, 2000)) {
            val id = history.start("read-$length")
            history.complete(id, true, " \n" + "x".repeat(length) + "\t ")
            assertEquals("x".repeat(minOf(length, 600)) + if (length > 600) "..." else "",
                history.entries.value.first().result)
        }
    }

    @Test fun evictionKeepsSixtyRowsAndLateCompletionCannotResurrectTheOldest() {
        val history = CommandHistory()
        val ids = (0..60).map { history.start("command-$it") }
        val before = history.entries.value
        assertEquals(60, before.size)
        assertEquals(ids.drop(1).reversed(), before.map { it.id })
        history.complete(ids.first(), true, "late")
        assertEquals(before, history.entries.value)
        history.complete(ids.last(), true, "latest")
        assertEquals("latest", history.entries.value.first().result)
        assertEquals(60, history.entries.value.size)
    }

    @Test fun clearKeepsIdsUniqueAndLateResultsCannotAffectANewerCommand() {
        val history = CommandHistory()
        val old = history.start("info")
        history.clear()
        history.complete(old, false, "late failure")
        assertTrue(history.entries.value.isEmpty())
        val newer = history.start("info")
        assertTrue(newer > old)
        history.complete(old, true, "late success")
        assertEquals(CmdStatus.RUNNING, history.entries.value.single().status)
        assertNull(history.entries.value.single().result)
        history.complete(newer, false, "Stopped.")
        assertEquals(CmdStatus.FAILED, history.entries.value.single().status)
        assertEquals("Stopped.", history.entries.value.single().result)
    }

    @Test fun unknownCompletionDoesNotAddOrAlterRows() {
        val history = CommandHistory()
        history.complete(42, false, "unknown")
        assertTrue(history.entries.value.isEmpty())
        history.start("uptime")
        val before = history.entries.value
        history.complete(42, true, "unknown")
        assertEquals(before, history.entries.value)
    }

    @Test fun concurrentWorkersKeepUniqueIdsAndEveryCompletionBelowTheCapacity() {
        val clock = AtomicLong(100)
        val history = CommandHistory { clock.incrementAndGet() }
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        try {
            val jobs = (0 until 8).map { worker ->
                pool.submit<List<Long>> {
                    check(go.await(10, TimeUnit.SECONDS))
                    (0 until 7).map { n ->
                        val command = "worker-$worker-$n"
                        val id = history.start(command)
                        history.complete(id, true, command)
                        id
                    }
                }
            }
            go.countDown()
            val ids = jobs.flatMap { it.get(10, TimeUnit.SECONDS) }
            assertEquals(56, ids.toSet().size)
            val entries = history.entries.value
            assertEquals(ids.toSet(), entries.map { it.id }.toSet())
            assertTrue(entries.all { it.status == CmdStatus.DONE && it.ok == true && it.result == it.text })
            assertTrue(entries.all { requireNotNull(it.doneAtMs) > it.sentAtMs })
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
