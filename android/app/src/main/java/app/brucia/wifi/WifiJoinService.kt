package app.brucia.wifi

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Device station join (Bruce "Connect to Wifi" parity): wifi_status, wifi_connect, wifi_disconnect
 * on a single-owner [DeviceOperation]. The join gets a longer timeout than the quick queries.
 */
class WifiJoinService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    /** Link state, progress or error text. */
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Ask whether the station is connected; the status is left unchanged while waiting. */
    fun refresh() = op.start("wifi_status", null) { ok, out -> if (ok) out.trim() else errorText(out) }

    /** Join an AP: wifi_connect <ssid>\u001f<pass> (0x1F lets ssid/pass contain spaces). */
    fun join(ssid: String, pass: String) {
        val s = ssid.trim()
        op.start(
            "wifi_connect $s\u001f$pass", "Connecting (up to ~10s)...",
            { if (s.isEmpty()) "Enter an SSID first." else null }, JOIN_TIMEOUT_MS,
        ) { ok, out -> if (ok) "Connected - IP ${out.trim()}" else "Failed: ${out.ifBlank { "timeout" }}" }
    }

    /** Drop the station link. */
    fun leave() = op.start("wifi_disconnect", null) { ok, out -> if (ok) "Disconnected." else errorText(out) }

    fun stop() = op.stop()

    companion object {
        const val TIMEOUT_MS = 10_000L
        /** The firmware's join itself waits up to ~10 s; leave room for DHCP and the reply. */
        const val JOIN_TIMEOUT_MS = 30_000L

        private fun errorText(out: String) = "error: ${out.ifBlank { "failed" }}"
    }
}
