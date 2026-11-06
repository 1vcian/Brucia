package app.brucia.monitor

import app.brucia.session.CommandRunner
import app.brucia.session.CommandTasks
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Bounded Wi-Fi jam detect (jam_detect <secs> -> "deauth=.. total=..") on one single-owner
 * [DeviceOperation]. A call while [busy] is ignored (the VM's `if (_jamDetectActive) return`); the
 * per-call timeout follows the window length. Defensive / authorized use.
 */
class JamDetectService(
    scope: CoroutineScope,
    runner: CommandRunner,
    private val isConnected: () -> Boolean,
    /** Carries `jam_detect_stop`: the firmware polls it inside the window and never ACKs it there. */
    private val stopRunner: CommandRunner = runner,
) {
    private val op = DeviceOperation(scope, runner, isConnected)
    private val stops = CommandTasks(scope)
    val status: StateFlow<String> = op.status

    /** True while a window is running (the VM's jamDetectActive). */
    val busy: StateFlow<Boolean> = op.busy

    /** Listen for [secs] seconds; ignored if one is already running. */
    fun run(secs: Int) {
        if (op.busy.value) return
        op.start("jam_detect $secs", "Listening for ${secs}s...", timeoutMs = timeoutMsFor(secs),
            precheck = { if (secs !in 1..120) "Duration must be 1–120 seconds." else null }) { ok, out ->
            out.ifBlank { if (ok) "Done." else "Failed." }
        }
    }

    /**
     * End a running window on the device: send the polled `jam_detect_stop` but keep waiting, so
     * [busy] clears with the device's final "deauth=.. total=.." reply. Without it the device kept
     * listening (up to 120 s, PINGs unanswered, other commands 409) after the app stopped waiting.
     * Nothing runs or no link: nothing is sent.
     */
    fun requestStop() {
        if (op.busy.value && isConnected()) stops.run(stopRunner, STOP_CMD, STOP_TIMEOUT_MS)
    }

    /** Stop waiting for the current reply (local only: disconnect, or after [requestStop]). */
    fun stop() = op.stop()

    companion object {
        const val STOP_CMD = "jam_detect_stop"
        /** Bounds only the fire-and-forget launch: the firmware polls the stop and never ACKs it. */
        const val STOP_TIMEOUT_MS = 10_000L
        // Clamp before arithmetic: even invalid callers cannot overflow or create an immediate timeout.
        fun timeoutMsFor(secs: Int): Long = (secs.coerceIn(1, 120).toLong() + 8) * 1000L
    }
}
