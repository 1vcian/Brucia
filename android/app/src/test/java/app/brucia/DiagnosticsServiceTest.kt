package app.brucia

import app.brucia.device.DiagnosticsService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Reuses GateRunner / settle from DeviceOperationTest; single-threaded, no wall-clock waits. */
class DiagnosticsServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    private var connected = true

    private fun CoroutineScope.svc() = DiagnosticsService(this, runner) { connected }

    private suspend fun answer(i: Int, ok: Boolean, out: String) {
        settle()
        assertTrue("call $i not sent", runner.calls.size > i)
        runner.reply(i, ok, out)
        settle()
    }

    @Test(timeout = 5_000) fun outputKeepsPreviousTextWhileWaitingThenShowsReply() = runBlocking {
        val s = svc()
        s.run("info")
        assertEquals("" to true, s.output.value to s.busy.value)
        answer(0, true, "Brucia v1\nSDK: x")
        assertEquals("Brucia v1\nSDK: x" to false, s.output.value to s.busy.value)
        s.run("free")
        settle()
        assertEquals("Brucia v1\nSDK: x" to true, s.output.value to s.busy.value)
        answer(1, false, "No response from the device.")
        assertEquals("No response from the device.", s.output.value)
    }

    @Test(timeout = 5_000) fun panelCommandsUseTheBoundedTimeout() = runBlocking {
        val s = svc()
        val panel = listOf("info", "free", "uptime", "i2c")
        panel.forEachIndexed { i, cmd ->
            s.run(cmd)
            answer(i, true, "")
        }
        assertEquals(panel.map { it to 20_000L }, runner.calls.toList())
        assertEquals("", s.output.value)
    }

    @Test(timeout = 5_000) fun otherCommandsAreRejectedLocally() = runBlocking {
        val s = svc()
        s.run("reboot")
        settle()
        assertTrue(runner.calls.isEmpty())
        assertEquals("Unsupported diagnostic: reboot" to false, s.output.value to s.busy.value)
    }

    @Test(timeout = 5_000) fun supersededReplyIgnoredAndStopShowsStopped() = runBlocking {
        val s = svc()
        s.run("i2c")
        settle()
        s.run("uptime")
        settle()
        runner.reply(0, true, "I2C device found at address 0x34")
        settle()
        assertEquals("" to true, s.output.value to s.busy.value)
        s.stop()
        runner.reply(1, true, "Uptime: 00:01:00")
        settle()
        assertEquals("Stopped." to false, s.output.value to s.busy.value)
    }

    @Test(timeout = 5_000) fun notConnectedSendsNothing() = runBlocking {
        connected = false
        val s = svc()
        s.run("info")
        settle()
        assertTrue(runner.calls.isEmpty())
        assertEquals("Connect the device over USB first.", s.output.value)
    }
}
