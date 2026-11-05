package app.brucia

import app.brucia.automation.Macro
import app.brucia.automation.MacroRunner
import app.brucia.automation.MacroStep
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandRunner
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MacroRunnerTest {
    private fun macro(vararg commands: String) = Macro("fixture", commands.map { MacroStep(it, 0) })
    private suspend fun until(condition: () -> Boolean) = withTimeout(2000) { while (!condition()) delay(1) }
    private suspend fun finished(service: MacroRunner) = until { !service.running.value }

    @Test(timeout = 5000) fun snapshotsStepsPreservesCommandsAndPausesAfterEachAck() = runVirtual {
        val events = mutableListOf<String>()
        val steps = mutableListOf(MacroStep("fixture first", 12), MacroStep("fixture second", 20))
        val service = MacroRunner(this, { CommandRunner { cmd, timeout ->
            assertEquals(650_000L, timeout)
            events += cmd
            true to ""
        } }, pause = { events += "pause $it" })
        service.start(Macro("snapshot", steps)); steps.clear()
        assertTrue(service.running.value)
        finished(service)
        assertEquals(listOf("fixture first", "pause 12", "fixture second", "pause 20"), events)
        assertEquals("Macro \"snapshot\" finished.", service.status.value)
    }

    @Test(timeout = 5000) fun rejectionStopsBeforePauseAndNextCommand() = runVirtual {
        val sent = mutableListOf<String>(); val pauses = mutableListOf<Long>()
        val service = MacroRunner(this, { CommandRunner { cmd, _ ->
            sent += cmd
            false to "module unavailable"
        } }, { pauses += it })
        service.start(Macro("failure", listOf(MacroStep("fixture", 500), MacroStep("never"))))
        finished(service)
        assertEquals(listOf("fixture"), sent)
        assertTrue(pauses.isEmpty())
        assertEquals("Step 1/2: fixture -> failed: module unavailable", service.status.value)
    }

    @Test(timeout = 5000) fun exceptionAndTimeoutRemainFailuresNotFinished() = runVirtual {
        for (timeout in listOf(false, true)) {
            var sent = 0
            val service = MacroRunner(this, { CommandRunner { _, _ ->
                sent++
                if (timeout) withTimeout(10) { awaitCancellation() } else throw java.io.IOException("link failed")
            } })
            service.start(macro("fixture", "never")); finished(service)
            assertEquals(1, sent)
            assertTrue(service.status.value.contains(if (timeout) "timed out" else "link failed"))
            assertTrue(service.status.value.contains("failed:"))
        }
    }

    @Test(timeout = 5000) fun emptyOrBlankStepsAndMissingConnectionSendNothing() = runVirtual {
        var connections = 0
        val service = MacroRunner(this, { connections++; null })
        service.start(macro()); assertFalse(service.running.value)
        service.start(macro("valid", "  ")); assertEquals(0, connections)
        service.start(macro("fixture")); assertEquals(1, connections)
        assertEquals("Connect the device over USB first.", service.status.value)
        assertFalse(service.running.value)
    }

    @Test(timeout = 5000) fun rapidSecondStartIsIgnoredWhileRunning() = runVirtual {
        var connections = 0
        val sent = mutableListOf<String>(); val gate = CompletableDeferred<Pair<Boolean, String>>()
        val service = MacroRunner(this, { connections++; CommandRunner { cmd, _ -> sent += cmd; gate.await() } })
        service.start(macro("first")); service.start(macro("second"))
        until { sent.isNotEmpty() }
        assertEquals(listOf("first"), sent); assertEquals(1, connections)
        gate.complete(true to "ok"); finished(service)
    }

    @Test(timeout = 5000) fun stopThenRestartCannotBeOverwrittenByOldNonCancellableReply() = runVirtual {
        var old: Continuation<Pair<Boolean, String>>? = null
        val gate = CompletableDeferred<Pair<Boolean, String>>()
        val sent = mutableListOf<String>()
        val service = MacroRunner(this, { CommandRunner { cmd, _ ->
            sent += cmd
            if (cmd == "old") suspendCoroutine { old = it } else gate.await()
        } })
        service.start(macro("old", "never")); until { old != null }
        service.stop(); assertFalse(service.running.value)
        service.start(macro("new")); until { sent.size == 2 }
        val current = service.status.value
        old!!.resume(true to "late")
        // Join the old child only; the new request remains blocked.
        coroutineContext[Job]!!.children.first().join()
        assertTrue(service.running.value)
        assertEquals(current, service.status.value)
        assertEquals(listOf("old", "new"), sent)
        gate.complete(true to "ok"); finished(service)
    }

    @Test(timeout = 5000) fun stopDuringPausePreventsNextCommand() = runVirtual {
        val sent = mutableListOf<String>(); val paused = CompletableDeferred<Unit>()
        val service = MacroRunner(this, { CommandRunner { cmd, _ -> sent += cmd; true to "ok" } }, {
            paused.complete(Unit); awaitCancellation()
        })
        service.start(Macro("pause", listOf(MacroStep("first", 500), MacroStep("never", 0))))
        paused.await(); service.stop()
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("first"), sent)
        assertFalse(service.running.value)
        assertTrue(service.status.value.startsWith("Macro stopped"))
    }

    @Test(timeout = 5000) fun cancelledParentBeforeOrDuringRunClearsRunning() = runVirtual {
        for (before in listOf(true, false)) {
            val parent = Job()
            if (before) parent.cancel()
            var sent = 0
            val service = MacroRunner(CoroutineScope(coroutineContext + parent), { CommandRunner { _, _ ->
                sent++; awaitCancellation()
            } })
            service.start(macro("first", "never"))
            if (!before) { until { sent == 1 }; parent.cancel() }
            parent.join(); finished(service)
            assertEquals(if (before) 0 else 1, sent)
            assertTrue(service.status.value.contains("cancelled"))
        }
    }

    @Test(timeout = 5000) fun directAndSavedMacrosUseSameDelayBounds() = runVirtual {
        val direct = Macro("bounds", listOf(MacroStep("first", -1), MacroStep("second", Int.MAX_VALUE)))
        val saved = Macro.fromJson(direct.toJson())
        for (input in listOf(direct, saved)) {
            val pauses = mutableListOf<Long>()
            val service = MacroRunner(this, { CommandRunner { _, _ -> true to "ok" } }, { pauses += it })
            service.start(input); finished(service)
            assertEquals(listOf(60_000L), pauses)
        }
    }

    private class Wire : Transport {
        val frames = mutableListOf<JSONObject>()
        override var isOpen = true
        override fun write(bytes: ByteArray) { frames += JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8)) }
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
    }
    private fun ack(frame: JSONObject, ok: Boolean = true, out: String = "ok") =
        JSONObject().put("v", 0).put("t", Kind.ACK).put("id", frame.getInt("id"))
            .put("d", JSONObject().put("ok", ok).put("out", out))

    @Test(timeout = 5000) fun realSessionTimeoutReleasesPendingAndStopsSequence() = runVirtual {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        val service = MacroRunner(this, { CommandRunner { cmd, _ -> session.await(cmd, 30) } })
        service.start(macro("first", "never")); finished(service)
        assertEquals(1, wire.frames.size)
        assertFalse(session.hasPending)
        assertTrue(service.status.value.contains("failed: No response from the device."))
    }

    @Test(timeout = 5000) fun realSessionStopReleasesPendingAndIgnoresLateAck() = runVirtual {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        val service = MacroRunner(this, { CommandRunner { cmd, timeout -> session.await(cmd, timeout) } })
        service.start(macro("first", "never")); until { wire.frames.isNotEmpty() }
        service.stop(); until { !session.hasPending }
        val stopped = service.status.value
        session.route(ack(wire.frames.single()))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(stopped, service.status.value)
        assertEquals(1, wire.frames.size)
    }

    @Test(timeout = 5000) fun disconnectAndReconnectNeverTransferOldSequenceToNewLink() = runVirtual {
        val oldWire = Wire(); val oldSession = CommandSession(CompanionClient(oldWire), oldWire)
        val newWire = Wire(); val newSession = CommandSession(CompanionClient(newWire), newWire)
        var current = oldSession
        val service = MacroRunner(this, {
            val captured = current
            CommandRunner { cmd, timeout -> captured.await(cmd, timeout) }
        })
        service.start(macro("first", "never")); until { oldWire.frames.isNotEmpty() }
        oldSession.close("Disconnected"); current = newSession
        finished(service)
        assertTrue(service.status.value.contains("Disconnected"))
        assertTrue(newWire.frames.isEmpty())
        service.start(macro("new")); until { newWire.frames.isNotEmpty() }
        oldSession.route(ack(oldWire.frames.single()))
        assertTrue(service.running.value)
        newSession.route(ack(newWire.frames.single(), false, "module unavailable"))
        finished(service)
        assertTrue(service.status.value.contains("failed: module unavailable"))
        assertFalse(oldSession.hasPending); assertFalse(newSession.hasPending)
    }
}
