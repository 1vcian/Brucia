package app.brucia

import app.brucia.session.LongOperation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * [LongOperation] composes DeviceOperation (exercised by DeviceOperationTest); here we cover the
 * early-stop seam: requestStop sends alongside without cancelling the wait, idle requestStop is a
 * no-op, abandon drops a late reply, the running flag lifecycle, and supersede on restart.
 * Reuses GateRunner / until from DeviceOperationTest. The LongOperation lives in a detached scope
 * (its status collector and fire-and-forget stop are long-lived) cancelled when the test ends.
 */
class LongOperationTest {
    private fun scoped(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val s = CoroutineScope(coroutineContext + Job())
        try { body(s) } finally { s.cancel() }
    }

    @Test(timeout = 5_000) fun runningFlagLifecycleAndResultMapping() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val op = LongOperation(s, runner, { true })
        assertFalse(op.running.value)
        op.start("responder 10", "Running...", 50_000) { ok, out -> if (ok) "ok:$out" else "no" }
        assertTrue(op.running.value)
        until { op.status.value == "Running..." }
        until { runner.calls.size == 1 }
        assertEquals("responder 10" to 50_000L, runner.calls[0])
        runner.reply(0, true, "x")
        until { !op.running.value }
        until { op.status.value == "ok:x" }
    }

    @Test(timeout = 5_000) fun requestStopSendsAlongsideAndFinalAckStillMaps() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val op = LongOperation(s, runner, { true })
        op.start("responder 10", "Running...", 50_000) { ok, out -> if (ok) "Done - $out" else "f" }
        until { runner.calls.size == 1 }
        op.requestStop("stop", null)
        until { runner.calls.size == 2 }
        assertEquals("stop", runner.cmds[1])
        assertTrue(op.running.value) // the wait for the original ACK is NOT cancelled
        runner.reply(0, true, "finished")
        until { !op.running.value }
        until { op.status.value == "Done - finished" }
    }

    @Test(timeout = 5_000) fun requestStopShowsStoppingTextThenFinal() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val op = LongOperation(s, runner, { true })
        op.start("brucegotchi 30", "Running...", 60_000) { ok, out -> if (ok) "Done - $out" else "f" }
        until { runner.calls.size == 1 }
        op.requestStop("brucegotchi_stop", "Stopping...")
        until { op.status.value == "Stopping..." }
        until { runner.cmds.contains("brucegotchi_stop") }
        runner.reply(0, true, "5")
        until { op.status.value == "Done - 5" }
    }

    @Test(timeout = 5_000) fun requestStopWhenIdleSendsNothing() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val op = LongOperation(s, runner, { true })
        op.requestStop("stop", "Stopping...")
        delay(20)
        assertTrue(runner.calls.isEmpty())
        op.start("responder 10", "Running...", 50_000) { ok, _ -> if (ok) "done" else "f" }
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !op.running.value }
        op.requestStop("stop", null) // idle again
        delay(20)
        assertEquals(1, runner.calls.size)
    }

    @Test(timeout = 5_000) fun abandonDropsALateReply() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val op = LongOperation(s, runner, { true })
        op.start("responder 10", "Running...", 50_000) { ok, out -> if (ok) "Done - $out" else "f" }
        until { runner.calls.size == 1 }
        op.abandon()
        until { !op.running.value }
        until { op.status.value == "Stopped." }
        runner.reply(0, true, "late")
        delay(20)
        assertEquals("Stopped.", op.status.value)
        assertFalse(op.running.value)
    }

    @Test(timeout = 5_000) fun supersedeOnRestartDropsOldReply() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val op = LongOperation(s, runner, { true })
        op.start("karma 10", "RunA", 50_000) { _, out -> "A:$out" }
        until { runner.calls.size == 1 }
        op.start("karma 20", "RunB", 50_000) { _, out -> "B:$out" }
        until { runner.calls.size == 2 }
        until { op.status.value == "RunB" }
        runner.reply(0, true, "OLD")
        delay(20)
        assertEquals("RunB", op.status.value) // superseded reply ignored
        runner.reply(1, true, "NEW")
        until { op.status.value == "B:NEW" }
    }
}
