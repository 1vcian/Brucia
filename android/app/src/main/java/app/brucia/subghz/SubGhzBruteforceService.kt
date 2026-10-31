package app.brucia.subghz

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Bounded Sub-GHz brute force (subghz_bruteforce <proto> <freqHz> <bits> <secs>) on one
 * single-owner [DeviceOperation]. A call while [busy] is ignored (the VM's
 * `if (_subBruteActive) return`); the per-call timeout follows the run length. Authorized use only.
 */
class SubGhzBruteforceService(scope: CoroutineScope, runner: CommandRunner, isConnected: () -> Boolean) {
    private val op = DeviceOperation(scope, runner, isConnected)
    val status: StateFlow<String> = op.status

    /** True while a run is in flight (the VM's subBruteActive). */
    val busy: StateFlow<Boolean> = op.busy

    /** Brute-force [protocolIdx] at [freqHz]/[bits] for [secs] seconds; ignored if one is running. */
    fun run(protocolIdx: Int, freqHz: Long, bits: Int, secs: Int) {
        if (op.busy.value) return
        op.start(
            "subghz_bruteforce $protocolIdx $freqHz $bits $secs",
            "Brute-forcing for ${secs}s...",
            timeoutMs = timeoutMsFor(secs),
        ) { ok, out -> out.ifBlank { if (ok) "Done." else "Failed." } }
    }

    /** Stop waiting for the current reply. */
    fun stop() = op.stop()

    companion object {
        // Firmware subghz.cpp clamps secs to [1, 30], so the run never exceeds the VM's (secs + 8) s
        // wait: keep that formula verbatim.
        fun timeoutMsFor(secs: Int): Long = (secs + 8) * 1000L
    }
}
