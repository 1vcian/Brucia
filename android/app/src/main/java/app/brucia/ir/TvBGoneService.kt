package app.brucia.ir

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * TV-B-Gone power-off sweep (`ir_tvbgone <region>`, region "na" or "eu") on a single-owner
 * [DeviceOperation]; the firmware bounds the sweep at 60 s and ACKs a summary line.
 */
class TvBGoneService(scope: CoroutineScope, runner: CommandRunner, isConnected: () -> Boolean) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    fun run(region: String = "na") =
        op.start("ir_tvbgone $region", "TV-B-Gone ($region) running...") { ok, out ->
            if (ok) out.ifBlank { "TV-B-Gone ($region) done." } else out.ifBlank { "TV-B-Gone ($region) failed." }
        }

    /** Stop waiting; the device finishes its bounded sweep and the late ACK is ignored. */
    fun stop() = op.stop()

    companion object {
        /** runTvBGoneHeadless(useEU, 60000) bound plus ACK margin. */
        const val TIMEOUT_MS = 75_000L
    }
}
