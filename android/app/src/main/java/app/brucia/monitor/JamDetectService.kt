package app.brucia.monitor

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Bounded Wi-Fi jam detect (jam_detect <secs> -> "deauth=.. total=..") on one single-owner
 * [DeviceOperation]. A call while [busy] is ignored (the VM's `if (_jamDetectActive) return`); the
 * per-call timeout follows the window length. Defensive / authorized use.
 */
class JamDetectService(scope: CoroutineScope, runner: CommandRunner, isConnected: () -> Boolean) {
    private val op = DeviceOperation(scope, runner, isConnected)
    val status: StateFlow<String> = op.status

    /** True while a window is running (the VM's jamDetectActive). */
    val busy: StateFlow<Boolean> = op.busy

    /** Listen for [secs] seconds; ignored if one is already running. */
    fun run(secs: Int) {
        if (op.busy.value) return
        op.start("jam_detect $secs", "Listening for ${secs}s...", timeoutMs = timeoutMsFor(secs)) { ok, out ->
            out.ifBlank { if (ok) "Done." else "Failed." }
        }
    }

    /** Stop waiting for the current reply. */
    fun stop() = op.stop()

    companion object {
        // Firmware wifi.cpp clamps secs to [1, 120], so the window never runs longer than the VM's
        // (secs + 8) s wait: keep that formula verbatim.
        fun timeoutMsFor(secs: Int): Long = (secs + 8) * 1000L
    }
}
