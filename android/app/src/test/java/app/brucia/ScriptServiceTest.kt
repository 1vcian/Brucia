package app.brucia

import app.brucia.device.ScriptService
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandRunner
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Decodes every written frame into its {v,t,id,d} envelope. */
private class ScriptFrameTransport : Transport {
    val frames = ArrayList<JSONObject>()
    override var isOpen = true
    override fun write(bytes: ByteArray) {
        val len = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)
        assertEquals(len, bytes.size - 4)
        frames += JSONObject(String(bytes, 4, len, Charsets.UTF_8))
    }
    override fun read(buf: ByteArray, timeoutMs: Int) = 0
    override fun close() { isOpen = false }
}

class ScriptServiceTest {
    private val runner = GateRunner()
    /** The direct stop sender; [GateRunner.auto] makes it return at once (as directStop does). */
    private val stopper = GateRunner().apply { auto = true to "" }

    private suspend fun ScriptService.result(reply: Pair<Boolean, String>, call: ScriptService.() -> Unit): String {
        runner.auto = reply
        call()
        until { !busy.value }
        return output.value
    }

    /** Start a run that waits for its reply (call 0 of [runner]). */
    private suspend fun running(svc: ScriptService, src: String = "while (true) { delay(100); }") {
        assertTrue(svc.run(src))
        until { runner.calls.size == 1 }
        assertEquals("Running..." to true, svc.output.value to svc.busy.value)
    }

    @Test fun runTextsCommandAndTimeout() = runVirtual {
        val svc = ScriptService(this, runner, { true }, stopper)
        assertEquals("hello\n", svc.result(true to "hello\n") {
            run(" print('hello') "); assertEquals("Running..." to true, output.value to busy.value)
        })
        assertEquals("(no output)", svc.result(true to " ") { run("1") })
        assertEquals("interpreter busy", svc.result(false to "interpreter busy") { run("1") })
        assertEquals("Script failed.", svc.result(false to "") { run("1") })
        assertEquals(listOf("script_run  print('hello') " to 25_000L, "script_run 1" to 25_000L,
            "script_run 1" to 25_000L, "script_run 1" to 25_000L), runner.calls.toList())
        assertTrue(stopper.calls.isEmpty())
    }

    @Test fun localRejectionsAreNotSent() = runVirtual {
        var connected = false
        val svc = ScriptService(this, runner, { connected }, stopper)
        svc.run("1")
        assertEquals("Connect the device over USB first.", svc.output.value)
        connected = true
        svc.run(" \n\t")
        assertEquals("Nothing to run.", svc.output.value)
        for (big in listOf("x".repeat(6001), "é".repeat(3001))) {
            svc.run(big)
            assertEquals("script too large" to false, svc.output.value to svc.busy.value)
        }
        delay(20)
        assertTrue(runner.calls.isEmpty())
        // At the limit after trimming (UTF-8 bytes, like the firmware's String.length()).
        assertNull(ScriptService.precheck("  " + "x".repeat(6000) + "\n"))
        assertNull(ScriptService.precheck("é".repeat(3000)))
    }

    @Test fun clearOnlyWhenIdleAndNeverCancelsARun() = runVirtual {
        var connected = true
        val svc = ScriptService(this, runner, { connected }, stopper)
        assertEquals("out", svc.result(true to "out") { run("1") })
        connected = false
        svc.clear()
        assertEquals("" to false, svc.output.value to svc.busy.value)
        connected = true
        runner.auto = null
        svc.run("2")
        until { runner.calls.size == 2 }
        svc.clear()
        assertEquals("Running..." to true, svc.output.value to svc.busy.value)
        runner.reply(1, true, "done")
        until { !svc.busy.value }
        assertEquals("done", svc.output.value)
        assertEquals(2, runner.calls.size)
    }

    @Test fun stopSendsOneScriptStopAndTheRunReplyShowsTheOutcome() = runVirtual {
        val svc = ScriptService(this, runner, { true }, stopper)
        running(svc)
        delay(2_000)
        assertTrue(svc.stop())
        assertEquals("Stopping..." to true, svc.output.value to svc.busy.value)
        assertTrue(svc.stopping.value)
        until { stopper.calls.size == 1 }
        assertEquals(listOf("script_stop" to ScriptService.STOP_SEND_TIMEOUT_MS), stopper.calls.toList())
        // Stop twice: no second frame. A new run is refused while the first still waits.
        assertFalse(svc.stop())
        assertFalse(svc.run("print(1)"))
        delay(50)
        assertEquals(1, stopper.calls.size)
        assertEquals(1, runner.calls.size)
        assertTrue(svc.stopping.value)
        // The device ends the run with its own reply; only then may the next script start.
        runner.reply(0, false, "script stopped")
        until { !svc.busy.value }
        assertEquals("Script stopped." to false, svc.output.value to svc.stopping.value)
        assertEquals("ok", svc.result(true to "ok") { assertTrue(run("print(1)")) })
        assertEquals(listOf("script_run while (true) { delay(100); }", "script_run print(1)"), runner.cmds)
        assertEquals(1, stopper.calls.size)
    }

    @Test fun stopWithNothingRunningOrAfterTheEndSendsNothing() = runVirtual {
        val svc = ScriptService(this, runner, { true }, stopper)
        assertFalse(svc.stop())
        assertEquals("" to false, svc.output.value to svc.stopping.value)
        assertEquals("done", svc.result(true to "done") { run("1") })
        assertFalse(svc.stop()) // the script already ended: its output stays
        svc.run(" ") // a local rejection is not a run either
        assertFalse(svc.stop())
        delay(50)
        assertTrue(stopper.calls.isEmpty())
        assertEquals("Nothing to run." to false, svc.output.value to svc.stopping.value)
    }

    @Test fun scriptThatEndsBeforeTheStopShowsItsOutputAndSaysSo() = runVirtual {
        val svc = ScriptService(this, runner, { true }, stopper)
        running(svc, "print('x')")
        assertTrue(svc.stop())
        runner.reply(0, true, "x\n")
        until { !svc.busy.value }
        assertEquals("x\n\n" + ScriptService.ENDED_FIRST, svc.output.value)
        assertFalse(svc.stopping.value)
        assertFalse(svc.stop())
        assertEquals(1, stopper.calls.size)
    }

    @Test fun stopWithoutEffectSaysSoAfterTheGraceAndShowsTheTimeout() = runVirtual {
        val svc = ScriptService(this, runner, { true }, stopper)
        running(svc)
        assertTrue(svc.stop())
        delay(ScriptService.STOP_GRACE_MS - 1)
        assertEquals("Stopping...", svc.output.value)
        delay(1)
        assertEquals(ScriptService.STOP_SLOW to true, svc.output.value to svc.busy.value)
        // Old firmware reads the stop only after the run: the reply is the wall-clock timeout.
        runner.reply(0, false, "script timeout")
        until { !svc.busy.value }
        assertEquals("script timeout\n" + ScriptService.ENDED_FIRST, svc.output.value)
        // A grace timer from an ended run never touches a later run.
        assertEquals("next", svc.result(true to "next") { run("1") })
    }

    @Test fun replyBeforeTheStopWriteReturnsKeepsRunBlockedUntilItDoes() = runVirtual {
        val slowStop = GateRunner()
        val svc = ScriptService(this, runner, { true }, slowStop)
        running(svc)
        assertTrue(svc.stop())
        until { slowStop.calls.size == 1 }
        runner.reply(0, false, "script stopped")
        until { !svc.busy.value }
        assertEquals("Script stopped.", svc.output.value)
        // The stop frame is not written yet: a new script_run could overtake it, so wait.
        assertTrue(svc.stopping.value)
        assertFalse(svc.run("print(2)"))
        assertFalse(svc.stop())
        slowStop.reply(0, true, "")
        until { !svc.stopping.value }
        assertTrue(svc.run("print(2)"))
        until { runner.calls.size == 2 }
        assertEquals("script_run print(2)", runner.cmds[1])
        runner.reply(1, true, "2")
        until { svc.output.value == "2" }
    }

    @Test fun disconnectDuringStopDropsTheWaitAndALateReply() = runVirtual {
        val slowStop = GateRunner()
        val svc = ScriptService(this, runner, { true }, slowStop)
        running(svc)
        assertTrue(svc.stop())
        until { slowStop.calls.size == 1 }
        svc.abandon()
        assertEquals(ScriptService.ABANDONED to false, svc.output.value to svc.busy.value)
        assertFalse(svc.stopping.value)
        runner.reply(0, false, "script stopped") // late: ignored
        slowStop.reply(0, true, "")
        delay(ScriptService.STOP_GRACE_MS + 100)
        assertEquals(ScriptService.ABANDONED, svc.output.value)
        assertFalse(svc.stop())
        svc.abandon() // idle: keeps the text
        assertEquals(ScriptService.ABANDONED, svc.output.value)
        assertEquals("again", svc.result(true to "again") { assertTrue(run("1")) })
        assertEquals(1, slowStop.calls.size)
    }

    @Test fun resultTexts() {
        val t = ScriptService.Companion
        assertEquals("Script stopped.", t.resultText(false, "script stopped", stopSent = true))
        assertEquals("Script stopped.", t.resultText(false, "script stopped", stopSent = false))
        assertEquals("interpreter busy", t.resultText(false, "interpreter busy", stopSent = false))
        assertEquals("script timeout", t.resultText(false, "script timeout", stopSent = false))
        assertEquals("(no output)\n" + t.ENDED_FIRST, t.resultText(true, "", stopSent = true))
        assertEquals("Disconnected", t.resultText(false, "Disconnected", stopSent = true))
        // Firmware from batch 16 reports an uncaught error as ok:false with the error as output.
        val err = "ReferenceError: variable 'print' is not defined\n    at <eval> (index.js:1:1)"
        assertEquals(err, t.resultText(false, err, stopSent = false))
    }

    @Test fun examplesUseOnlyOutputBruceJsDefines() {
        // BruceJS defines console.log but no global print() (mqjs_stdlib.c); print() threw on hardware.
        val call = Regex("""(?<![\w.$])([A-Za-z_$][\w$]*)\s*\(""")
        for ((name, src) in scriptExamples) {
            assertTrue(name, "console.log(" in src)
            assertEquals(name, emptyList<String>(), call.findAll(src).map { it.groupValues[1] }.filter { it != "for" }.toList())
        }
    }

    /** The real session layer: exact frames on the wire, replies routed by id, close on disconnect. */
    @Test fun framesOnTheWireThroughTheCommandSession() = runVirtual {
        val t = ScriptFrameTransport()
        val client = CompanionClient(t)
        val session = CommandSession(client, t, preflight = { cmd ->
            app.brucia.protocol.CommandCapabilities.check(cmd, null)
        })
        val viaSession = CommandRunner { cmd, timeoutMs -> session.await(cmd, timeoutMs) }
        val direct = CommandRunner { cmd, _ -> client.cmd(cmd); true to "" } // as the ViewModel's directStop
        val svc = ScriptService(this, viaSession, { !session.isClosed }, direct)
        fun cmdOf(f: JSONObject) = f.getJSONObject("d").getString("cmd")
        fun reply(id: Int, ok: Boolean, out: String) = session.route(JSONObject().put("v", 0).put("t", Kind.ACK)
            .put("id", id).put("d", JSONObject().put("ok", ok).put("out", out)))

        assertTrue(svc.run("while (true) { delay(100); }"))
        until { t.frames.size == 1 }
        delay(2_000)
        assertTrue(svc.stop())
        assertFalse(svc.stop())
        until { t.frames.size == 2 }
        val (runFrame, stopFrame) = t.frames
        for (f in t.frames) {
            assertEquals(setOf("v", "t", "id", "d"), f.keys().asSequence().toSet())
            assertEquals(0, f.getInt("v"))
            assertEquals(Kind.CMD, f.getInt("t"))
            assertEquals(setOf("cmd"), f.getJSONObject("d").keys().asSequence().toSet())
        }
        assertEquals("script_run while (true) { delay(100); }", cmdOf(runFrame))
        assertEquals("script_stop", cmdOf(stopFrame))
        assertNotEquals(runFrame.getInt("id"), stopFrame.getInt("id"))
        assertTrue(session.hasPending) // only the script_run waits; the stop is never ACKed
        reply(runFrame.getInt("id"), false, "script stopped")
        until { !svc.busy.value && !svc.stopping.value }
        assertEquals("Script stopped.", svc.output.value)
        assertFalse(session.hasPending)

        // A late reply to the stop id (e.g. a stop that reached an idle device) is unmatched and changes nothing.
        reply(stopFrame.getInt("id"), true, "late")
        assertEquals(1L, client.counters.snapshot().unmatchedReplies)
        assertEquals("Script stopped.", svc.output.value)

        // The next script starts at once after the stopped reply.
        assertTrue(svc.run("print('after stop')"))
        until { t.frames.size == 3 }
        assertEquals("script_run print('after stop')", cmdOf(t.frames[2]))
        reply(t.frames[2].getInt("id"), true, "after stop\n")
        until { !svc.busy.value }
        assertEquals("after stop\n", svc.output.value)

        // Disconnect while stopping: the pending run fails with the session's reason, no more frames.
        assertTrue(svc.run("while (true) { delay(100); }"))
        until { t.frames.size == 4 }
        assertTrue(svc.stop())
        until { t.frames.size == 5 }
        assertEquals("script_stop", cmdOf(t.frames[4]))
        session.close("Disconnected")
        until { !svc.busy.value && !svc.stopping.value }
        assertEquals("Disconnected", svc.output.value)
        svc.abandon()
        assertEquals("Disconnected", svc.output.value)
        assertFalse(svc.stop())
        svc.run("1")
        assertEquals("Connect the device over USB first.", svc.output.value)
        delay(ScriptService.STOP_GRACE_MS + 100)
        assertEquals(5, t.frames.size)
    }

    @Test fun localTimeoutThenALateRunReplyIsIgnored() = runVirtual {
        val t = ScriptFrameTransport()
        val client = CompanionClient(t)
        val session = CommandSession(client, t)
        val svc = ScriptService(this, { cmd, ms -> session.await(cmd, ms) }, { true }, { cmd, _ -> client.cmd(cmd); true to "" })
        assertTrue(svc.run("while (true) { delay(100); }"))
        until { t.frames.size == 1 }
        assertTrue(svc.stop())
        delay(ScriptService.TIMEOUT_MS)
        until { !svc.busy.value }
        assertEquals("No response from the device.", svc.output.value)
        session.route(JSONObject().put("v", 0).put("t", Kind.ACK).put("id", t.frames[0].getInt("id"))
            .put("d", JSONObject().put("ok", false).put("out", "script stopped")))
        assertEquals("No response from the device.", svc.output.value)
        assertFalse(svc.stopping.value)
        assertTrue(svc.run("1"))
        until { t.frames.size == 3 }
        assertEquals(listOf("script_run while (true) { delay(100); }", "script_stop", "script_run 1"),
            t.frames.map { it.getJSONObject("d").getString("cmd") })
    }
}
