package app.brucia.wardrive

import app.brucia.ir.IrButton
import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Replays a geo-pinned signal (a saved `rf tx ...` command or an IR payload) on a single-owner
 * [DeviceOperation] and reports what the device answered, instead of announcing "Replayed"
 * before anything was sent. Authorized use only (your own gates/remotes).
 */
class GeoReplayService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    timeoutMs: Long = DeviceOperation.DEFAULT_TIMEOUT_MS,
) {
    private val op = DeviceOperation(scope, runner, isConnected, timeoutMs)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    fun replay(name: String, payload: String) =
        op.start(command(payload), "Replaying \"$name\"...", { if (payload.isBlank()) NOTHING else null }) { ok, out ->
            if (ok) "Replayed \"$name\"." else "Replay of \"$name\" failed: ${out.ifBlank { "no response" }}"
        }

    fun stop() = op.stop()

    companion object {
        const val NOTHING = "Nothing to replay."

        /** The device command for a saved payload (same mapping as the saved-signal replay). */
        fun command(payload: String): String =
            if (payload.startsWith("rf tx")) payload else IrButton.replayable(payload)
    }
}
