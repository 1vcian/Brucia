package app.brucia.audio

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Onboard mic recording (mic_record) on one single-owner [DeviceOperation]: the device records
 * a WAV to storage and replies with its path once done, so the wait covers the duration.
 */
class MicRecordService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Record [secs] seconds; the device clamps to [MAX_SECS]. */
    fun record(secs: Int) {
        val precheck = { if (secs <= 0) "Enter a duration in seconds." else null }
        op.start("mic_record $secs", "Recording ${secs}s...", precheck, timeoutFor(secs)) { ok, out ->
            if (ok) out.ifBlank { "Recording saved." } else out.ifBlank { "Recording failed." }
        }
    }

    /** Stop waiting; the device still finishes and saves the recording. */
    fun stop() = op.stop()

    companion object {
        const val MAX_SECS = 30
        const val MARGIN_MS = 10_000L

        /** Recording time (as clamped by the device) plus a margin for the save and reply. */
        fun timeoutFor(secs: Int): Long = secs.coerceIn(0, MAX_SECS) * 1000L + MARGIN_MS
    }
}
