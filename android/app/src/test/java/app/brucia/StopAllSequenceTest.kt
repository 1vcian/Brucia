package app.brucia

import app.brucia.lora.LoraService
import app.brucia.session.CommandRunner
import app.brucia.session.JsonCommandRunner
import app.brucia.session.StopAllSequence
import app.brucia.wifi.EvilPortalService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * F26: the global Stop ends a blocking operation through its own stop before any other service's
 * stop is sent, so the firmware's companionPollStop (which takes ANY `stop`/`*_stop` as the end of
 * the running operation, unanswered) cannot swallow another service's stop.
 */
class StopAllSequenceTest {

    /**
     * Firmware model: frames arrive in order. While lora_recv runs, a `stop`/`*_stop` CMD ends it
     * and is consumed WITHOUT a reply; any other CMD gets 409 busy. Otherwise every CMD is ACKed.
     */
    private class PollingDevice : CommandRunner, JsonCommandRunner {
        val frames = CopyOnWriteArrayList<String>()
        private var recv: CompletableDeferred<Pair<Boolean, JSONObject?>>? = null

        /** One CMD frame on the wire: its reply, or null when the polled stop consumed it. */
        fun frame(cmd: String): Pair<Boolean, String>? {
            frames += cmd
            val r = recv
            if (r != null) {
                if (cmd == "stop" || cmd.endsWith("_stop")) {
                    recv = null
                    r.complete(true to JSONObject().put("out", "received 0 packet(s) (stopped)"))
                    return null
                }
                return false to "device busy"
            }
            return true to "ok:$cmd"
        }

        override suspend fun run(cmd: String, timeoutMs: Long): Pair<Boolean, String> =
            frame(cmd) ?: run { delay(timeoutMs); false to "No response from the device." }

        override suspend fun runJson(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?> {
            frames += cmd
            return CompletableDeferred<Pair<Boolean, JSONObject?>>().also { recv = it }.await()
        }
    }

    @Test(timeout = 5_000) fun withoutBlockingOperationRestRunsAtOnce() = runVirtual {
        var rest = 0
        val seq = StopAllSequence(listOf(StopAllSequence.Blocking("lora", { false }) { fail("nothing to stop") }))
        assertNull(seq.run(this) { rest++ })
        assertEquals(1, rest)
    }

    @Test(timeout = 5_000) fun restWaitsForTheBlockingOperationThenRunsOnce() = runVirtual {
        var active = true
        var requested = 0
        var rest = 0
        val seq = StopAllSequence(listOf(StopAllSequence.Blocking("lora", { active }) { requested++ }))
        val job = seq.run(this) { rest++ }
        assertNotNull(job)
        assertEquals(1, requested)
        seq.run(this) { rest++ } // repeated Stop while waiting: no second request or rest
        assertEquals(1, requested)
        delay(100)
        assertEquals(0, rest)
        active = false
        job!!.join()
        assertEquals(1, rest)
    }

    @Test(timeout = 5_000) fun restNotRunWhenTheOperationIgnoresItsStopPastTheBound() = runVirtual {
        // F26: an op still active at the bound (e.g. firmware that ignores the stop) must NOT trigger
        // the unrelated service stops, or they would be consumed by that still-polling operation.
        var rest = 0; var timedOut = 0
        val seq = StopAllSequence(listOf(StopAllSequence.Blocking("old-fw", { true }) {}), settleMs = 1_000)
        seq.run(this, onTimeout = { timedOut++ }) { rest++ }
        delay(1_100)
        assertEquals(0, rest)
        assertEquals(1, timedOut)
    }

    @Test(timeout = 5_000) fun cancelDropsThePendingRest() = runVirtual {
        var rest = 0
        val seq = StopAllSequence(listOf(StopAllSequence.Blocking("lora", { true }) {}), settleMs = 1_000)
        seq.run(this) { rest++ }
        seq.cancel() // link lost: these stops must not reach a replacement device
        delay(2_000)
        assertEquals(0, rest)
    }

    @Test(timeout = 5_000) fun aFailingRequestDoesNotBlockTheOthers() = runVirtual {
        var bActive = true
        val errors = mutableListOf<String>()
        val seq = StopAllSequence(listOf(
            StopAllSequence.Blocking("a", { false }) {},
            StopAllSequence.Blocking("b", { bActive }) { bActive = false; throw IllegalStateException("x") },
        ), onError = { n, _ -> errors += n })
        var rest = 0
        seq.run(this) { rest++ }?.join()
        assertEquals(listOf("b"), errors)
        assertEquals(1, rest)
    }

    /** Real LoRa + Evil Portal services against the polling model: no lost stop, no lost ACK. */
    @Test(timeout = 5_000) fun loraReceiveEndsFirstThenThePortalStopIsAcknowledged() = runVirtual {
        val device = PollingDevice()
        val lora = LoraService(this, device, device, { true }, stopRunner = { cmd, _ -> device.frame(cmd); true to "" }) {}
        val portal = EvilPortalService(this, device, { true }, { _, _ -> true to "" }, {})
        portal.start("Lab")
        until { portal.active.value }
        lora.receive(60)
        until { lora.receiving.value && device.frames.size == 2 }

        val seq = StopAllSequence(listOf(StopAllSequence.Blocking("lora", { lora.receiving.value }) { lora.requestStopReceive() }))
        seq.run(this) { lora.stop(); portal.stop() }?.join()
        until { !portal.busy.value }

        assertEquals(listOf("evil_portal_start Lab\t\t1\t0", "lora_recv 60", "stop", "evil_portal_stop"), device.frames)
        assertFalse(portal.active.value)
        assertEquals("ok:evil_portal_stop", portal.status.value)
        assertEquals("received 0 packet(s) (stopped)", lora.status.value)
        assertEquals(true to "ok:info", device.run("info", 1_000)) // a fresh benign command is served
    }

    /** The defect the ordering removes: sent together, the portal stop ends LoRa and is never answered. */
    @Test(timeout = 5_000) fun unorderedStopLetsLoraConsumeThePortalStop() = runVirtual {
        val device = PollingDevice()
        val lora = LoraService(this, device, device, { true }, stopRunner = { cmd, _ -> device.frame(cmd); true to "" }) {}
        val portal = EvilPortalService(this, device, { true }, { _, _ -> true to "" }, {})
        portal.start("Lab")
        until { portal.active.value }
        lora.receive(60)
        until { lora.receiving.value && device.frames.size == 2 }

        lora.stop() // the old lifecycle hook: local only, no frame
        portal.stop()
        until { device.frames.size == 3 }
        delay(EvilPortalService.TIMEOUT_MS + 1_000)
        assertEquals("evil_portal_stop", device.frames.last())
        // F27 keeps the result honest: the portal is not reported off.
        assertTrue(portal.active.value)
        assertTrue(portal.status.value.startsWith(EvilPortalService.STOP_FAILED))
    }

    /** F26: if a blocking op is still active at the timeout, rest is NOT run and onTimeout fires. */
    @Test(timeout = 5_000) fun timeoutWithStillActiveSkipsRestAndReports() = runVirtual {
        var rest = 0; var timedOut = 0
        val seq = StopAllSequence(listOf(StopAllSequence.Blocking("stuck", { true }) {}), settleMs = 1_000)
        val job = seq.run(this, onTimeout = { timedOut++ }) { rest++ }
        assertNotNull(job)
        delay(1_100)
        assertEquals(0, rest)       // unrelated stops never dispatched into the active op
        assertEquals(1, timedOut)
    }

    /** F26: if the op settles before the timeout, rest runs and onTimeout does not. */
    @Test(timeout = 5_000) fun settleBeforeTimeoutRunsRestNotOnTimeout() = runVirtual {
        var active = true; var rest = 0; var timedOut = 0
        val seq = StopAllSequence(listOf(StopAllSequence.Blocking("op", { active }) {}), settleMs = 5_000)
        val job = seq.run(this, onTimeout = { timedOut++ }) { rest++ }
        delay(100); active = false
        job!!.join()
        assertEquals(1, rest); assertEquals(0, timedOut)
    }
}
