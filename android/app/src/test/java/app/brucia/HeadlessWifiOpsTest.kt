package app.brucia

import app.brucia.wifi.BrucegotchiService
import app.brucia.wifi.KarmaService
import app.brucia.wifi.ResponderService
import app.brucia.wifi.headlessTimeoutMs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The three headless Wi-Fi services: exact ViewModel texts, commands, firmware-derived timeouts,
 * stop frames and the not-connected guard. Reuses GateRunner / until from DeviceOperationTest;
 * each service lives in a detached scope cancelled when the test ends.
 */
class HeadlessWifiOpsTest {
    private fun scoped(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val s = CoroutineScope(coroutineContext + Job())
        try { body(s) } finally { s.cancel() }
    }

    private val responderRunning = "Running 10s - join the device to the target Wi-Fi first (Connect tool). " +
        "Answering LLMNR/NBT-NS name queries with the device IP (authorized use)... (Stop to end early)"
    private val karmaRunning = "Running 10s - the device answers clients' probe requests to lure them " +
        "(authorized use). The device screen shows Karma... (Stop to end early)"
    private val brucegotchiRunning = "Running 10s - the device screen shows the gotchi, handshakes save to " +
        "/BrucePCAP/handshakes... (Stop to end early)"

    @Test(timeout = 5_000) fun timeoutsFollowFirmwareClampAndDefaults() {
        // wifi.cpp clamps responder/karma/brucegotchi to 5..600 s; sub-min falls back to a default.
        assertEquals(40_000L, headlessTimeoutMs(10, 60))   // 10 s + 30 s margin
        assertEquals(90_000L, headlessTimeoutMs(0, 60))    // responder/karma default 60 s
        assertEquals(60_000L, headlessTimeoutMs(0, 30))    // brucegotchi default 30 s
        assertEquals(630_000L, headlessTimeoutMs(700, 30)) // clamped to 600 s
    }

    @Test(timeout = 5_000) fun responderTextsCommandTimeoutAndStop() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val svc = ResponderService(s, runner, { true })
        svc.start(10)
        assertTrue(svc.running.value)
        until { runner.calls.size == 1 }
        assertEquals("responder 10" to 40_000L, runner.calls[0])
        until { svc.status.value == responderRunning }
        runner.reply(0, true, "")
        until { !svc.running.value }
        assertEquals("Done - finished", svc.status.value)
        svc.start(10)
        until { runner.calls.size == 2 }
        runner.reply(1, false, "")
        until { svc.status.value == "Failed: error" }
        svc.start(10)
        until { runner.calls.size == 3 }
        svc.stop()
        until { runner.calls.size == 4 }
        assertEquals("stop", runner.cmds[3])
    }

    @Test(timeout = 5_000) fun karmaTextsCommandTimeoutAndStop() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val svc = KarmaService(s, runner, { true })
        svc.start(10)
        until { runner.calls.size == 1 }
        assertEquals("karma 10" to 40_000L, runner.calls[0])
        until { svc.status.value == karmaRunning }
        runner.reply(0, false, "")
        until { svc.status.value == "error: failed" }
        svc.start(10)
        until { runner.calls.size == 2 }
        runner.reply(1, true, "lured 3")
        until { svc.status.value == "Done - lured 3" }
        svc.start(10)
        until { runner.calls.size == 3 }
        svc.stop()
        until { runner.calls.size == 4 }
        assertEquals("stop", runner.cmds[3])
    }

    @Test(timeout = 5_000) fun brucegotchiTextsCommandTimeoutAndStop() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val svc = BrucegotchiService(s, runner, { true })
        svc.start(10)
        until { runner.calls.size == 1 }
        assertEquals("brucegotchi 10" to 40_000L, runner.calls[0])
        until { svc.status.value == brucegotchiRunning }
        svc.stop()
        until { svc.status.value == "Stopping..." }
        until { runner.cmds.contains("brucegotchi_stop") }
        assertTrue(svc.running.value)
        runner.reply(0, true, "7")
        until { svc.status.value == "Done - 7" }
        svc.start(10)
        until { runner.calls.contains("brucegotchi 10" to 40_000L) && runner.calls.size >= 3 }
        runner.reply(2, false, "")
        until { svc.status.value == "error: failed" }
    }

    @Test(timeout = 5_000) fun notConnectedShowsPromptWithoutSending() = scoped { s ->
        val runner = GateRunner().apply { sticky = true }
        val responder = ResponderService(s, runner, { false }).also { it.start(10) }
        val karma = KarmaService(s, runner, { false }).also { it.start(10) }
        val gotchi = BrucegotchiService(s, runner, { false }).also { it.start(10) }
        val prompt = "Connect the device over USB first."
        until { responder.status.value == prompt && karma.status.value == prompt && gotchi.status.value == prompt }
        assertFalse(responder.running.value)
        assertFalse(karma.running.value)
        assertFalse(gotchi.running.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }
}
