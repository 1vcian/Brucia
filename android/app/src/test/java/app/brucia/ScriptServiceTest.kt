package app.brucia

import app.brucia.device.ScriptService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScriptServiceTest {
    private val runner = GateRunner()

    private suspend fun ScriptService.result(reply: Pair<Boolean, String>, call: ScriptService.() -> Unit): String {
        runner.auto = reply
        call()
        until { !busy.value }
        return output.value
    }

    @Test fun runTextsCommandAndTimeout() = runVirtual {
        val svc = ScriptService(this, runner, { true })
        assertEquals("hello\n", svc.result(true to "hello\n") {
            run(" print('hello') "); assertEquals("Running..." to true, output.value to busy.value)
        })
        assertEquals("(no output)", svc.result(true to " ") { run("1") })
        assertEquals("interpreter busy", svc.result(false to "interpreter busy") { run("1") })
        assertEquals("Script failed.", svc.result(false to "") { run("1") })
        assertEquals(listOf("script_run  print('hello') " to 25_000L, "script_run 1" to 25_000L,
            "script_run 1" to 25_000L, "script_run 1" to 25_000L), runner.calls.toList())
    }

    @Test fun localRejectionsAreNotSent() = runVirtual {
        var connected = false
        val svc = ScriptService(this, runner, { connected })
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
        val svc = ScriptService(this, runner, { connected })
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
}
