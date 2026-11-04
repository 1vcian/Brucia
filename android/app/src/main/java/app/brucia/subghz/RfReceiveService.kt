package app.brucia.subghz

import app.brucia.device.CliParse
import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot Sub-GHz receive over the Bruce CLI (`rf rx <freqHz>`, device decodes) on a
 * single-owner [DeviceOperation]. A newer capture supersedes a pending one; only the owning
 * successful reply updates [last] and reaches [onCaptured] with the fields to persist.
 */
class RfReceiveService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    private val onCaptured: (Captured) -> Unit,
) {
    /** A decoded capture: [payload] is a replay command when one can be built, else the raw output. */
    data class Captured(val name: String, val protocol: String, val payload: String)

    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val _last = MutableStateFlow<String?>(null)
    /** Output of the last answered capture (null until one answers). */
    val last: StateFlow<String?> = _last.asStateFlow()
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    fun capture(freqHz: Long) = op.start("rf rx $freqHz", null) { ok, out ->
        _last.value = out
        if (ok) {
            val key = CliParse.field(out, "Key") ?: CliParse.field(out, "key") ?: "signal"
            onCaptured(Captured(key, CliParse.field(out, "Protocol") ?: "", CliParse.rfReplayCommand(out) ?: out))
        }
        null
    }

    /** Cancel the pending capture; its late reply is ignored. */
    fun stop() = op.stop()

    companion object {
        /** rfReceiveSignal(freq, 10, ...): 10 x 1 s polls plus one final poll and radio init. */
        const val TIMEOUT_MS = 20_000L
    }
}
