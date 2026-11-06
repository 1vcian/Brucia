package app.brucia

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F25 wiring check (source-level; the ViewModel needs Android): while Remote owns USB every framed
 * path refuses, and the global Stop ends Remote before sending framed stops. Behaviour of the
 * ordering itself is covered by StopAllSequenceTest; leaving the screen by StreamLeaveHooksTest.
 * Not a substitute for the phone check Remote -> Home -> Device info / Files.
 */
class RemoteLinkGateTest {
    private val vm = File("src/main/java/app/brucia/wardrive/WardriveViewModel.kt").readText()

    private fun assertIn(what: String, needle: String) = assertTrue("$what: missing `$needle`", vm.contains(needle))

    @Test fun framedPathsRefuseWhileRemoteOwnsTheLink() {
        val gate = "check(!remoteOwnsLink) { REMOTE_OWNS_LINK }"
        assertTrue("session preflight and direct stops both gate", Regex(Regex.escape(gate)).findAll(vm).count() == 2)
        assertIn("SUB supplier", "session.takeUnless { remoteOwnsLink }")
        // Set only after a confirmed companion_exit (F34), which is itself after the hand-over stops.
        val set = vm.indexOf("remoteOwnsLink = true")
        assertTrue("gate set after companion_exit is awaited", set > vm.indexOf("session?.await(\"companion_exit\""))
        assertTrue("gate set after the hand-over", set > vm.indexOf("remoteHandover.stopRunning()"))
    }

    @Test fun stopRemoteAndDisconnectReleaseTheGate() {
        val stopRemote = vm.substringAfter("fun stopRemote() {").substringBefore("\n    }\n")
        assertTrue(stopRemote.contains("remoteOwnsLink = false"))
        assertTrue("handback waits for CAPS", stopRemote.contains("remoteHandback = true") && stopRemote.contains("_device.value = null"))
        val disconnect = vm.substringAfter("private fun disconnectLink(").substringBefore("\n    }\n")
        assertTrue(disconnect.contains("remoteOwnsLink = false") && disconnect.contains("stopAllSequence.cancel()"))
    }

    @Test fun globalStopEndsRemoteFirst() {
        val seq = vm.substringAfter("private val stopAllSequence by lazy {").substringBefore("\n    }\n")
        assertTrue("remote is the first blocking step", seq.indexOf("\"remote\"") in 0 until seq.indexOf("\"lora\""))
        assertTrue(seq.contains("lora.requestStopReceive()"))
        val stopAll = vm.substringAfter("fun stopAllRunning() {").substringBefore("\n    }\n")
        assertTrue("device stops run after the blocking phase", stopAll.contains("stopAllSequence.run(viewModelScope, onTimeout"))
    }

    @Test fun handoverCoversPolledWindowsAndAwaitsBurstDrain() {
        val handover = vm.substringAfter("private val remoteHandover by lazy {").substringBefore("\n    }\n")
        for (step in listOf("\"IR power spam\"", "\"LoRa receive\"", "\"jam detect\"")) {
            assertTrue("handover missing step $step", handover.contains(step))
        }
        assertTrue("IR handover sends its stop", handover.contains("irPowerSpam.requestStop()"))
        assertTrue("LoRa handover sends its stop", handover.contains("lora.requestStopReceive()"))
        assertTrue("jam handover sends its stop", handover.contains("jamDetector.requestStop()"))
        assertTrue("burst step awaits the NRF24 drain", handover.contains("nrf24Loops.any { it.draining.value }"))
        assertTrue("polled windows awaited to end", handover.contains("awaitWhileBounded"))
    }

    @Test fun startRemoteAwaitsCompanionExitBeforeTakingTheLink() {
        val start = vm.substringAfter("fun startRemote() {").substringBefore("\n    // Transport writes go")
        val await = start.indexOf("session?.await(\"companion_exit\"")
        assertTrue("companion_exit is awaited", await >= 0)
        // The gate goes up only after a confirmed exit, and the framed reader is cancelled after it.
        assertTrue("await precedes taking the link", await < start.indexOf("remoteOwnsLink = true"))
        assertTrue("await precedes cancelling the reader", await < start.indexOf("readJob?.cancel()"))
        // A failed/absent exit aborts Remote instead of entering raw mode.
        val abort = start.indexOf("if (!exit.first) {")
        assertTrue("failed exit is handled", abort in 0 until start.indexOf("remoteOwnsLink = true"))
        assertTrue("abort clears running", start.substring(abort, start.indexOf("remoteOwnsLink = true")).contains("_remoteRunning.value = false"))
    }
}
