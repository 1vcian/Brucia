package app.brucia.device

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Headless JavaScript run on the device (script_run <src>) on a single-owner [DeviceOperation].
 * The firmware's size limit is checked locally so an oversized source is never sent.
 */
class ScriptService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    /** Captured script output, progress or error text. */
    val output: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Run [src], sent as given (the firmware trims it). */
    fun run(src: String) = op.start("script_run $src", "Running...", { precheck(src) }) { ok, out ->
        if (ok) out.ifBlank { "(no output)" } else out.ifBlank { "Script failed." }
    }

    /**
     * Clear the output when idle. While a script runs this is a no-op: clearing must not supersede
     * the run, whose reply would be lost while the device keeps executing.
     */
    fun clear() { op.clearStatus() }

    /** Stop waiting for the reply; the device still finishes the script (bounded by the firmware). */
    fun stop() = op.stop()

    companion object {
        /** Firmware bounds execution at 15 s (20 s wall clock); leave margin for the reply. */
        const val TIMEOUT_MS = 25_000L
        /** Firmware limit on the trimmed source (String.length(), i.e. UTF-8 bytes). */
        const val MAX_SOURCE_BYTES = 6000

        /** Local rejection text, or null when [src] may be sent. */
        fun precheck(src: String): String? = when {
            src.isBlank() -> "Nothing to run."
            src.trim().toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES -> "script too large"
            else -> null
        }
    }
}
