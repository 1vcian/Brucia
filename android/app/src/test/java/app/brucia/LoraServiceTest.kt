package app.brucia

import app.brucia.lora.LoraService
import app.brucia.session.JsonCommandRunner
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** JSON twin of GateRunner: waits on a per-call gate, surviving cancellation so ownership is testable. */
internal class JsonGate : JsonCommandRunner {
    val gates = CopyOnWriteArrayList<CompletableDeferred<Pair<Boolean, JSONObject?>>>()
    val calls = CopyOnWriteArrayList<Pair<String, Long>>()
    override suspend fun runJson(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?> {
        val gate = CompletableDeferred<Pair<Boolean, JSONObject?>>().also { gates += it }
        calls += cmd to timeoutMs
        return withContext(NonCancellable) { gate.await() }
    }
    fun reply(i: Int, ok: Boolean, d: JSONObject?) { gates[i].complete(ok to d) }
}

class LoraServiceTest {
    @Test(timeout = 5000) fun configureOrSendSupersedesReceiveAndClearsItsIndicator() = runVirtual {
        val s = svc()
        for (configure in listOf(true, false)) {
            val i = json.calls.size
            val j = runner.calls.size
            s.receive(5)
            until { json.calls.size == i + 1 }
            if (configure) s.configure("868") else s.send("test")
            assertFalse(s.receiving.value)
            until { runner.calls.size == j + 1 }
            json.reply(i, true, JSONObject().put("data", JSONObject().put("data", "late")))
            runner.reply(j, true, "ok")
            until { !s.busy.value }
            assertTrue(packets.isEmpty())
        }
    }

    @Test(timeout = 5000) fun cancelledScopeBeforeOrDuringReceiveClearsIndicator() = runVirtual {
        for (before in listOf(true, false)) {
            val parent = Job().apply { if (before) cancel() }
            val entered = CompletableDeferred<Unit>()
            val scope = CoroutineScope(parent + virtualUnconfined)
            val s = LoraService(scope, runner, JsonCommandRunner { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }, { true }) { fail("Cancelled receive delivered data") }
            s.receive(5)
            if (!before) entered.await()
            parent.cancelAndJoin()
            assertFalse(s.receiving.value)
            assertFalse(s.busy.value)
        }
    }

    @Test(timeout = 5000) fun invalidReceiveDurationNeverArmsIndicatorOrSends() = runVirtual {
        val s = svc()
        for (seconds in listOf(Int.MIN_VALUE, 0, 121, Int.MAX_VALUE)) {
            s.receive(seconds)
            assertFalse(s.receiving.value)
            assertFalse(s.busy.value)
            assertTrue(s.status.value.contains("Duration must"))
            assertTrue(LoraService.recvTimeoutFor(seconds) in 11_000L..130_000L)
        }
        assertTrue(json.calls.isEmpty())
    }

    private val runner = GateRunner().apply { sticky = true }
    private val json = JsonGate()
    private val packets = CopyOnWriteArrayList<JSONObject>()
    private fun CoroutineScope.svc(connected: Boolean = true) =
        LoraService(this, runner, json, { connected }, stopRunner = { _, _ -> true to "" }) { packets += it }

    @Test(timeout = 5000) fun notConnectedRejectsLocallyAndSendsNothing() = runVirtual {
        val s = svc(connected = false)
        s.configure("868"); s.send("hi"); s.receive(20)
        assertEquals("Connect the device over USB first.", s.status.value)
        assertFalse(s.receiving.value)
        delay(20)
        assertTrue(runner.calls.isEmpty() && json.calls.isEmpty())
    }

    @Test(timeout = 5000) fun configureBuildsCommandDropsTrailingBlanksAndMapsResult() = runVirtual {
        val s = svc()
        s.configure("", "125")
        assertEquals("Enter a frequency in MHz.", s.status.value)
        assertTrue(runner.calls.isEmpty())

        s.configure(" 868 ", "125", "", "7")
        assertEquals("Applying LoRa config...", s.status.value)
        until { runner.calls.size == 1 }
        assertEquals("lora_config 868 125  7" to 10_000L, runner.calls[0])
        runner.reply(0, true, "")
        until { !s.busy.value }
        assertEquals("LoRa configured.", s.status.value)

        s.configure("868")
        until { runner.calls.size == 2 }
        assertEquals("lora_config 868", runner.cmds[1])
        runner.reply(1, false, "")
        until { !s.busy.value }
        assertEquals("No LoRa module on this device.", s.status.value)
    }

    @Test(timeout = 5000) fun sendRejectsBlankAndMapsResult() = runVirtual {
        val s = svc()
        s.send("   ")
        assertEquals("Enter a message to send.", s.status.value)
        assertTrue(runner.calls.isEmpty())

        s.send(" hi ")
        assertEquals("Sending..." to true, s.status.value to s.busy.value)
        until { runner.calls.size == 1 }
        assertEquals("lora_send hi" to 650_000L, runner.calls[0])
        runner.reply(0, true, "ok")
        until { !s.busy.value }
        assertEquals("ok", s.status.value)
    }

    @Test(timeout = 5000) fun receiveTogglesFlagDeliversPacketOnceAndTimesOutBySeconds() = runVirtual {
        val s = svc()
        s.receive(20)
        assertTrue(s.receiving.value)
        assertEquals("Listening for LoRa packets (20s)...", s.status.value)
        until { json.calls.size == 1 }
        assertEquals("lora_recv 20" to 30_000L, json.calls[0])
        json.reply(0, true, JSONObject().put("out", "").put("data", JSONObject().put("data", "AB")))
        until { !s.receiving.value }
        assertEquals("Receive complete.", s.status.value)
        assertEquals(1, packets.size)
        assertEquals("AB", packets[0].optString("data"))
    }

    @Test(timeout = 5000) fun supersededReceiveNeverDeliversNorClearsNewerFlag() = runVirtual {
        val s = svc()
        s.receive(5)
        until { json.calls.size == 1 }
        s.receive(5) // supersede; first reply must be ignored
        until { json.calls.size == 2 }
        json.reply(0, true, JSONObject().put("data", JSONObject().put("data", "stale")))
        delay(20)
        assertTrue(s.receiving.value)        // newer flag untouched
        assertTrue(packets.isEmpty())        // stale packet dropped
        json.reply(1, true, JSONObject().put("data", JSONObject().put("data", "fresh")))
        until { !s.receiving.value }
        assertEquals(listOf("fresh"), packets.map { it.optString("data") })
    }

    @Test(timeout = 5000) fun stopReceiveCancelsWaitAndSuppressesLatePacket() = runVirtual {
        val s = svc()
        s.receive(5)
        until { json.calls.size == 1 }
        s.stopReceive()
        assertFalse(s.receiving.value)
        assertEquals(1, json.calls.size)
        assertFalse(s.busy.value)
        assertEquals("Stopped.", s.status.value)
        json.reply(0, true, JSONObject().put("out", "received 1 packet(s)").put("data", JSONObject().put("data", "X")))
        delay(20)
        assertEquals("Stopped.", s.status.value)
        assertTrue(packets.isEmpty())
    }

    @Test(timeout = 5000) fun stopReceiveSendsDeviceStopSoTheListenWindowEndsEarly() = runVirtual {
        // The firmware blocks in lora_recv for up to 120 s; only a polled `stop` frees it early
        // (otherwise PINGs go unanswered and the link watchdog reports a device restart).
        val stopsSent = CopyOnWriteArrayList<String>()
        val s = LoraService(this, runner, json, { true }, stopRunner = { cmd, _ -> stopsSent += cmd; true to "" }) { packets += it }
        s.receive(60)
        until { json.calls.size == 1 }
        s.stopReceive()
        until { stopsSent.isNotEmpty() }
        assertEquals(listOf("stop"), stopsSent)
        assertFalse(s.receiving.value)
        // Not receiving: no stray stop frame.
        s.stopReceive()
        settle()
        assertEquals(1, stopsSent.size)
        // The device's final ACK for the stopped window is ignored (releases the gated fake).
        json.reply(0, true, JSONObject().put("out", "received 0 packet(s) (stopped)"))
        settle()
        assertTrue(packets.isEmpty())
    }

    @Test(timeout = 5000) fun stopCancelsAndClearsFlagIgnoringLateReply() = runVirtual {
        val s = svc()
        s.receive(5)
        until { json.calls.size == 1 }
        s.stop()
        assertFalse(s.receiving.value)
        until { !s.busy.value }
        assertEquals("Stopped.", s.status.value)
        json.reply(0, true, JSONObject().put("data", JSONObject().put("data", "late")))
        delay(20)
        assertTrue(packets.isEmpty())
        assertFalse(s.receiving.value)
    }

    /**
     * F32 teardown: models the VM stop runner, which resolves the session only when its coroutine
     * runs. The gate holds that start; meanwhile the link is torn down and replaced by B.
     */
    private class HeldLinkStops {
        val gate = CompletableDeferred<Unit>()
        @Volatile var link = "A"
        val written = CopyOnWriteArrayList<Pair<String, String>>()
        val runner = app.brucia.session.CommandRunner { cmd, _ -> gate.await(); written += link to cmd; true to "" }
    }

    @Test(timeout = 5000) fun disconnectCancelsQueuedReceiveStopSoItNeverReachesTheReplacementLink() = runVirtual {
        val held = HeldLinkStops()
        val s = LoraService(this, runner, json, { true }, stopRunner = held.runner) { packets += it }
        s.receive(60); until { json.calls.size == 1 }
        s.requestStopReceive()   // Stop all / Remote hand-over: stop queued, not yet written
        s.abandon()              // disconnect
        held.link = "B"          // replacement session selected
        held.gate.complete(Unit) // the held sender would run now
        settle()
        assertTrue("old stop reached ${held.written}", held.written.isEmpty())
        // The fence is lifted: a receive on the new link is not permanently refused.
        s.receive(20); until { json.calls.size == 2 }
        assertTrue(s.receiving.value)
        s.abandon()
        json.reply(0, true, null); json.reply(1, true, null)
    }

    @Test(timeout = 5000) fun explicitStopReceiveStillWritesItsStopToTheSameLink() = runVirtual {
        val held = HeldLinkStops()
        val s = LoraService(this, runner, json, { true }, stopRunner = held.runner) { packets += it }
        s.receive(60); until { json.calls.size == 1 }
        s.stopReceive()          // user Stop receive: local cancel must not drop the device stop
        assertFalse(s.receiving.value)
        held.gate.complete(Unit)
        until { held.written.isNotEmpty() }
        assertEquals(listOf("A" to LoraService.STOP_CMD), held.written)
        s.receive(20); until { json.calls.size == 2 } // fence cleared once written
        s.abandon()
        json.reply(0, true, null); json.reply(1, true, null)
    }

    @Test(timeout = 5000) fun pendingStopFencesRestartUntilWrittenOrAbandoned() = runVirtual {
        val held = HeldLinkStops()
        val s = LoraService(this, runner, json, { true }, stopRunner = held.runner) { packets += it }
        s.receive(60); until { json.calls.size == 1 }
        s.stopReceive()
        s.receive(20); settle()
        assertEquals("restart refused while the stop is unwritten", 1, json.calls.size)
        s.abandon()
        s.receive(20); until { json.calls.size == 2 }
        s.abandon()
        held.gate.complete(Unit); settle()
        assertTrue(held.written.isEmpty())
        json.reply(0, true, null); json.reply(1, true, null)
    }
}
