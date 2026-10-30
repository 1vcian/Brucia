package app.brucia.device

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** One-shot ESP-NOW broadcast (espnow_send <message>) on a single-owner [DeviceOperation]. */
class EspNowService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    /** Broadcast progress or result text. */
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Broadcast the trimmed [message]; the firmware truncates it to its payload size. */
    fun send(message: String) {
        val m = message.trim()
        op.start("espnow_send $m", "Broadcasting...", { if (m.isEmpty()) "Enter a message first." else null }) { ok, out ->
            if (ok) out.ifBlank { "Broadcast sent." } else out.ifBlank { "Broadcast failed." }
        }
    }

    fun stop() = op.stop()

    companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
