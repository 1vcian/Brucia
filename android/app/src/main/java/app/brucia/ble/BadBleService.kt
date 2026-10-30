package app.brucia.ble

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Run a DuckyScript over BLE HID (ble_badble <script>) on one single-owner [DeviceOperation]:
 * the latest request wins. Blank scripts are rejected locally; the raw script text is sent
 * exactly as the ViewModel sends it (no trimming). Authorized / lab use only.
 */
class BadBleService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Send the script over BLE; empty text is rejected without sending. */
    fun run(script: String) {
        val precheck = { if (script.isBlank()) "Script is empty." else null }
        op.start("ble_badble $script", "Running over BLE...", precheck) { ok, out ->
            if (ok) out.ifBlank { "Done." } else out.ifBlank { "Failed." }
        }
    }
    /** Stop waiting for the current reply; the device run is untouched. */
    fun stop() = op.stop()

    companion object {
        // No total firmware bound: bluetooth.cpp runBleBadble has a 30 s pairing wait
        // (millis() + 30000), but key_input then plays the whole script (DuckyScript DELAYs are
        // unbounded), so the ACK can arrive long after. Keep the ViewModel's 650 s wait.
        const val TIMEOUT_MS = 650_000L
    }
}
