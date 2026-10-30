package app.brucia

import app.brucia.session.BurstLoop
import app.brucia.session.BurstLoopRegistry
import app.brucia.session.CommandRunner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Reuses [GateRunner] and [until] from DeviceOperationTest. Non-sticky by default so a Stop
 * (which cancels the job) frees any pending await and runBlocking does not hang. */
class BurstLoopTest {
    private val registry = BurstLoopRegistry()
    private var linkOpen = true
    private val runner = GateRunner()

    private fun CoroutineScope.loop(r: CommandRunner = runner) =
        BurstLoop(this, r, { linkOpen }, registry)
    private fun BurstLoop.state() = status.value to active.value
    /** Wait for burst [i] to be sent, then answer it. */
    private suspend fun answer(i: Int, ok: Boolean, out: String) {
        until { runner.calls.size == i + 1 }
        runner.reply(i, ok, out)
    }

    @Test(timeout = 5_000) fun burstsAreSequentialOnePerReply() = runBlocking {
        val loop = loop()
        loop.start("run", { "burst" }, 8000)
        until { runner.calls.size == 1 }
        assertEquals("run" to true, loop.state())
        delay(20)
        assertEquals(1, runner.calls.size) // no next burst until the reply lands
        runner.reply(0, true, "")
        until { runner.calls.size == 2 }
        assertEquals(listOf("burst", "burst"), runner.cmds.toList())
        loop.stop()
    }

    @Test(timeout = 5_000) fun timeoutIsPassedToRunner() = runBlocking {
        val loop = loop()
        loop.start("run", { "b" }, 4321)
        until { runner.calls.size == 1 }
        assertEquals(4321L, runner.calls[0].second)
        loop.stop()
    }

    @Test(timeout = 5_000) fun stopEndsLoopAndRestoresStoppedText() = runBlocking {
        val loop = loop()
        loop.start("run", { "b" }, 8000, stoppedText = "Idle.")
        until { runner.calls.size == 1 }
        loop.stop()
        assertEquals("Idle." to false, loop.state())
    }

    @Test(timeout = 5_000) fun silentStopKeepsStatus() = runBlocking {
        val loop = loop()
        loop.start("run", { "b" }, 8000)
        until { runner.calls.size == 1 }
        loop.stop(silent = true)
        assertEquals("run" to false, loop.state())
    }

    @Test(timeout = 5_000) fun restartReplacesLoopAndOldJobNeverResetsNewState() = runBlocking {
        val loop = loop()
        loop.start("first", { "a" }, 8000)
        until { runner.calls.size == 1 }
        loop.start("second", { "b" }, 8000) // supersedes the first run
        until { runner.calls.size == 2 }
        assertEquals("second" to true, loop.state())
        runner.reply(0, false, "late reject") // a stray old reply must not reset the new state
        delay(20)
        assertEquals("second" to true, loop.state())
        loop.stop()
    }

    @Test(timeout = 5_000) fun rejectionEndsLoopWithReplyText() = runBlocking {
        val loop = loop()
        loop.start("run", { "b" }, 8000)
        answer(0, false, "no module")
        until { !loop.active.value }
        assertEquals("no module" to false, loop.state())
        delay(20)
        assertEquals(1, runner.calls.size) // did not continue past the rejection
    }

    @Test(timeout = 5_000) fun blankFailureContinues() = runBlocking {
        val loop = loop()
        loop.start("run", { "b" }, 8000)
        answer(0, false, "") // blank failure keeps looping (as the VM)
        until { runner.calls.size == 2 }
        assertEquals("run" to true, loop.state())
        loop.stop()
    }

    @Test(timeout = 5_000) fun runnerExceptionEndsLoopWithMessageOrFailed() = runBlocking {
        for ((msg, text) in listOf("boom" to "boom", null to "Failed.", "" to "Failed.")) {
            val loop = loop(CommandRunner { _, _ -> throw IllegalStateException(msg) })
            loop.start("run", { "b" }, 8000)
            until { !loop.active.value }
            assertEquals(text, loop.status.value)
        }
    }

    @Test(timeout = 5_000) fun linkClosedEndsLoop() = runBlocking {
        val loop = loop()
        loop.start("run", { "b" }, 8000)
        until { runner.calls.size == 1 }
        linkOpen = false
        runner.reply(0, true, "") // loop resumes, re-checks the link, then exits
        until { !loop.active.value }
        assertEquals("Stopped." to false, loop.state())
    }

    @Test(timeout = 5_000) fun stopAllStopsEveryLoopIncludingLaterOnes() = runBlocking {
        val rA = GateRunner(); val rB = GateRunner()
        val a = loop(rA)
        a.start("A", { "a" }, 8000)
        until { rA.calls.size == 1 }
        val b = loop(rB) // registered and started after A
        b.start("B", { "b" }, 8000)
        until { rB.calls.size == 1 }
        registry.stopAll(silent = false)
        until { !a.active.value && !b.active.value }
        assertEquals("Stopped.", a.status.value)
        assertEquals("Stopped.", b.status.value)
    }

    @Test(timeout = 5_000) fun cancellationPropagatesToRunner() = runBlocking {
        val sawCancel = CompletableDeferred<Unit>()
        val probe = CommandRunner { _, _ ->
            try { CompletableDeferred<Pair<Boolean, String>>().await() }
            catch (e: CancellationException) { sawCancel.complete(Unit); throw e }
        }
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val loop = BurstLoop(scope, probe, { linkOpen }, registry)
        loop.start("run", { "b" }, 8000)
        until { loop.active.value }
        scope.cancel()
        sawCancel.await() // the cancellation reached the in-flight burst
        until { !loop.active.value }
    }
    @Test(timeout = 5_000) fun sharedStatusLineAndOnEndOnlyForOwnEnd() = runBlocking {
        val line = kotlinx.coroutines.flow.MutableStateFlow("")
        val scan = BurstLoop(this, runner, { linkOpen }, registry, line)
        val sniff = BurstLoop(this, runner, { linkOpen }, registry, line)
        var ends = 0
        scan.start("Scanning...", { "scan" }, 8000, "Scan stopped.", onEnd = { ends++ })
        answer(0, false, "No NRF24 module")
        until { !scan.active.value }
        assertEquals("No NRF24 module", line.value)
        assertEquals(1, ends)
        sniff.start("Sniffing...", { "sniff" }, 8000, "Sniff stopped.")
        until { runner.calls.size == 2 }
        scan.stop() // another loop's running text on the shared line is left alone
        assertEquals("Sniffing..." to true, sniff.state())
        sniff.stop()
        assertEquals("Sniff stopped.", line.value)
        scan.start("Scanning...", { "scan" }, 8000, "Scan stopped.", onEnd = { ends++ })
        until { runner.calls.size == 3 }
        scan.stop() // an explicit stop leaves cleanup to the caller
        delay(20)
        assertEquals(1, ends)
    }
}
