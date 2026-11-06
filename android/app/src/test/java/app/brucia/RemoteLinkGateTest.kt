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
        // Set after the hand-over stops (which still need framed replies), before companion_exit.
        val set = vm.indexOf("remoteOwnsLink = true")
        assertTrue("gate set before companion_exit", set in 0 until vm.indexOf("client?.cmd(\"companion_exit\")"))
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
        assertTrue("device stops run after the blocking phase", stopAll.contains("stopAllSequence.run(viewModelScope) {"))
    }
}
