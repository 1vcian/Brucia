package app.brucia

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Shared fake: replies [auto] at once, else waits on a gate per call (outliving cancellation if [sticky]). */
internal class GateRunner : CommandRunner {
    val gates = CopyOnWriteArrayList<CompletableDeferred<Pair<Boolean, String>>>()
    val calls = CopyOnWriteArrayList<Pair<String, Long>>()
    val cmds get() = calls.map { it.first }
    var auto: Pair<Boolean, String>? = null
    var sticky = false

    override suspend fun run(cmd: String, timeoutMs: Long): Pair<Boolean, String> {
        val gate = CompletableDeferred<Pair<Boolean, String>>().also { gates += it }
        calls += cmd to timeoutMs
        auto?.let { return it }
        return if (sticky) withContext(NonCancellable) { gate.await() } else gate.await()
    }

    fun reply(i: Int, ok: Boolean, out: String) { gates[i].complete(ok to out) }
}

/** Bounded polling: lets service coroutines run without fixed sleeps. */
internal suspend fun until(cond: () -> Boolean) = withTimeout(2_000) { while (!cond()) delay(1) }

/** Let every ready coroutine on the single-threaded runBlocking loop run; no wall-clock wait. */
internal suspend fun settle() = repeat(50) { yield() }

class DeviceOperationTest {
    private val runner = GateRunner().apply { sticky = true }

    private fun CoroutineScope.op(r: CommandRunner = runner, connected: Boolean = true) =
        DeviceOperation(this, r, { connected }, 1234)
    private fun DeviceOperation.go(precheck: () -> String? = { null }) =
        start("cmd", "Running...", precheck) { ok, out -> if (ok) "ok:$out" else out.ifBlank { "fallback" } }
    private fun DeviceOperation.state() = status.value to busy.value
    /** Wait for call [i], complete it, and return the final status. */
    private suspend fun DeviceOperation.answer(i: Int, ok: Boolean, out: String): String {
        until { runner.calls.size == i + 1 }
        runner.reply(i, ok, out)
        until { !busy.value }
        return status.value
    }

    @Test fun notConnectedSkipsPrecheckAndSendsNothing() = runBlocking {
        val op = op(connected = false)
        op.go { fail("precheck ran"); null }
        assertEquals("Connect the device over USB first." to false, op.state())
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test fun busyLifecycleMappingAndTimeout() = runBlocking {
        val op = op()
        op.go()
        assertEquals("Running..." to true, op.state())
        assertEquals("ok:x", op.answer(0, true, "x"))
        op.go()
        assertEquals("fallback", op.answer(1, false, ""))
        assertEquals(listOf("cmd" to 1234L, "cmd" to 1234L), runner.calls.toList())
    }

    @Test fun runnerExceptionBecomesFailureText() = runBlocking {
        var message: String? = null
        val op = op(CommandRunner { _, _ -> throw IllegalStateException(message) })
        for ((m, text) in listOf("boom" to "boom", null to "fallback")) {
            message = m
            op.go()
            until { !op.busy.value }
            assertEquals(text, op.status.value)
        }
    }

    @Test fun localRejectAndStopSupersedeInFlight() = runBlocking {
        val op = op()
        op.stop()
        assertEquals("", op.status.value)
        op.go()
        until { runner.calls.size == 1 }
        op.go { "rejected" }
        assertEquals("rejected" to false, op.state())
        op.stop()
        assertEquals("rejected", op.status.value)
        op.go()
        until { runner.calls.size == 2 }
        op.stop()
        assertEquals("Stopped." to false, op.state())
        runner.reply(0, true, "late")
        runner.reply(1, true, "late")
        delay(20)
        assertEquals("Stopped." to false, op.state())
    }

    @Test fun supersedeIgnoresOldReplyAndNullRunningKeepsStatus() = runBlocking {
        val op = op()
        op.go()
        until { runner.calls.size == 1 }
        op.start("cmd", null) { _, out -> out }
        until { runner.calls.size == 2 }
        runner.reply(0, true, "OLD")
        delay(20)
        assertEquals("Running..." to true, op.state())
        assertEquals("NEW", op.answer(1, true, "NEW"))
    }

    @Test fun perCallTimeoutOverridesOnlyThatCommand() = runBlocking {
        runner.auto = true to ""
        val op = op()
        op.start("a", null, timeoutMs = 30_000) { _, _ -> "a" }
        until { !op.busy.value }
        op.start("b", null) { _, _ -> "b" }
        until { op.status.value == "b" }
        assertEquals(listOf("a" to 30_000L, "b" to 1234L), runner.calls.toList())
    }
    @Test fun clearStatusOnlyWhenIdle() = runBlocking {
        val op = op()
        op.go()
        assertFalse(op.clearStatus())
        assertEquals("Running..." to true, op.state())
        assertEquals("ok:x", op.answer(0, true, "x"))
        assertTrue(op.clearStatus())
        assertEquals("" to false, op.state())
    }

    @Test fun nullResultKeepsStatusAndClearsBusy() = runBlocking {
        val op = op()
        op.go()
        assertEquals("ok:x", op.answer(0, true, "x"))
        op.start("cmd", null) { _, _ -> null }
        assertEquals("ok:x" to true, op.state())
        assertEquals("ok:x", op.answer(1, false, "err"))
    }
    @Test fun startJsonDeliversDataSharesOwnershipAndMapsErrors() = runBlocking {
        val replies = CopyOnWriteArrayList<CompletableDeferred<Pair<Boolean, org.json.JSONObject?>>>()
        val json = app.brucia.session.JsonCommandRunner { cmd, timeoutMs ->
            assertEquals("eth_status" to 777L, cmd to timeoutMs)
            CompletableDeferred<Pair<Boolean, org.json.JSONObject?>>().also { replies += it }.await()
        }
        val op = op()
        op.startJson(json, "eth_status", "Querying...", timeoutMs = 777) { ok, d -> "$ok:${d?.optString("ip")}" }
        assertEquals("Querying..." to true, op.state())
        until { replies.size == 1 }
        replies[0].complete(true to org.json.JSONObject().put("ip", "10.0.0.2"))
        until { !op.busy.value }
        assertEquals("true:10.0.0.2", op.status.value)

        // A newer JSON command owns the status; the superseded reply is ignored.
        op.startJson(json, "eth_status", "first", timeoutMs = 777) { _, _ -> "stale" }
        until { replies.size == 2 }
        op.startJson(json, "eth_status", "second", timeoutMs = 777) { _, d -> d?.optString("msg") }
        until { replies.size == 3 }
        replies[1].complete(true to null)
        delay(20)
        assertEquals("second" to true, op.state())
        replies[2].completeExceptionally(IllegalStateException("boom"))
        until { !op.busy.value }
        assertEquals("boom", op.status.value)
    }

    @Test fun showIfIdleNeverCancelsAPendingWait() = runBlocking {
        val op = op()
        assertTrue(op.showIfIdle("idle note"))
        assertEquals("idle note" to false, op.state())
        op.go()
        until { runner.calls.size == 1 }
        assertFalse(op.showIfIdle("late note"))
        assertEquals("Running..." to true, op.state())
        assertEquals("ok:x", op.answer(0, true, "x"))
        assertTrue(op.showIfIdle("after"))
        assertEquals("after" to false, op.state())
    }
}
