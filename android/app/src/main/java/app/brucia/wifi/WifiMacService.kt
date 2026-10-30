package app.brucia.wifi

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Device STA MAC read / change (wifi_get_mac, wifi_set_mac <mac|random>) on a single-owner
 * [DeviceOperation]. The MAC is validated locally like the firmware's validateMACFormat.
 */
class WifiMacService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    timeoutMs: Long = DeviceOperation.DEFAULT_TIMEOUT_MS,
) {
    private val op = DeviceOperation(scope, runner, isConnected, timeoutMs)
    /** Current MAC, progress or error text. */
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Ask for the current STA MAC; the status is left unchanged while waiting. */
    fun get() = op.start("wifi_get_mac", null) { ok, out -> if (ok) out.trim() else errorText(out) }

    /** Set and persist the STA MAC; blank input means "random". */
    fun set(mac: String) {
        val m = mac.trim().ifBlank { "random" }
        op.start("wifi_set_mac $m", "Setting...", { if (isValidMacArg(m)) null else USAGE }) { ok, out ->
            if (ok) out.trim().ifBlank { "MAC set." } else errorText(out)
        }
    }

    fun stop() = op.stop()

    companion object {
        const val USAGE = "error: usage: wifi_set_mac <aa:bb:cc:dd:ee:ff|random>"

        /** "random" (any case) or aa:bb:cc:dd:ee:ff, hex in either case. */
        fun isValidMacArg(mac: String): Boolean = mac.equals("random", ignoreCase = true) ||
            mac.length == 17 && mac.withIndex().all { (i, c) ->
                if (i % 3 == 2) c == ':' else c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            }

        private fun errorText(out: String) = "error: ${out.ifBlank { "failed" }}"
    }
}
