package app.brucia.ibutton

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * iButton (1-Wire) id read / write over a [CommandRunner]. Single owner: every [read] or
 * [write] supersedes the in-flight operation, and a generation token keeps a superseded or
 * stopped operation from touching [status] or [busy] afterwards. Checks run in order:
 * connection, interface, then (for writes) the id, validated like the firmware does.
 */
class IButtonService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    private val hasInterface: () -> Boolean,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val op = DeviceOperation(scope, runner, isConnected, timeoutMs)
    val status: StateFlow<String> = op.status
    /** True only while the current operation awaits its reply. */
    val busy: StateFlow<Boolean> = op.busy

    /** Read one key id; the firmware polls the probe briefly and answers "id=<16 hex>". */
    fun read() = op.start("ibutton_read", "Reading - touch an iButton to the probe...", { noInterface() }) { ok, out ->
        out.ifBlank { if (ok) "No iButton read." else "Read failed." }
    }

    /** Write [hex] (':' and ' ' ignored, exactly 16 hex chars) to an RW1990 blank. */
    fun write(hex: String) {
        val id = normalizeId(hex)
        val precheck = {
            noInterface() ?: when {
                id.isEmpty() -> "Enter an id to write."
                !isValidId(id) -> INVALID_ID
                else -> null
            }
        }
        op.start("ibutton_write $id", "Writing $id...", precheck) { ok, out ->
            out.ifBlank { if (ok) "Wrote $id." else "Write failed." }
        }
    }

    /** Cancel the in-flight operation, if any; its late reply is ignored. */
    fun stop() = op.stop()

    private fun noInterface(): String? = if (hasInterface()) null else NO_INTERFACE

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val NO_INTERFACE = "No confirmed iButton interface"
        const val INVALID_ID = "invalid id (need 16 hex chars)"

        /** Firmware normalization: drop ':' and ' ', then trim. */
        fun normalizeId(hex: String): String = hex.replace(":", "").replace(" ", "").trim()

        fun isValidId(id: String): Boolean =
            id.length == 16 && id.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
