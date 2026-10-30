package app.brucia.ble

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot BLE media-control hijack (ble_media <mac>) on one single-owner [DeviceOperation].
 * The MAC is trimmed and validated locally with the ViewModel's regex before anything is sent.
 * Authorized / lab use only.
 */
class BleMediaService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Connect to [mac] and inject the media-control sequence; a malformed MAC is rejected. */
    fun send(mac: String) {
        val m = mac.trim()
        val precheck = { if (!MAC_REGEX.matches(m)) "Enter a MAC like AA:BB:CC:DD:EE:FF." else null }
        op.start("ble_media $m", "Connecting to $m...", precheck) { ok, out ->
            out.ifBlank { if (ok) "Sent." else "Failed." }
        }
    }

    /** Stop waiting for the current reply. */
    fun stop() = op.stop()

    companion object {
        // No single firmware bound: bluetooth.cpp ble_media calls runMediaCommandHijackHeadless ->
        // executeAudioAttack -> attemptConnectionWithStrategies, a cascade of connect attempts
        // (aggressive 12 s, exploit 15 s x3, HFP 8 s, plus delays) with no overall cap. Keep the
        // ViewModel's 650 s wait.
        const val TIMEOUT_MS = 650_000L
        val MAC_REGEX = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    }
}
