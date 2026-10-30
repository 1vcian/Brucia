package app.brucia.wifi

import app.brucia.session.CommandRunner
import app.brucia.session.LongOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin services for the three long, bounded, early-stoppable Wi-Fi ops, each on its own
 * [LongOperation]. Texts, commands and stop frames match the ViewModel 1:1; they add no
 * capability. Firmware (src/companion/wifi.cpp) clamps every one of these to [MIN_SECS]..
 * [MAX_SECS] seconds (sub-minimum falls back to a per-op default), so the ACK wait is derived
 * from that bounded run length plus a margin via [headlessTimeoutMs].
 */

/** Firmware seconds clamp shared by responder / karma / brucegotchi (wifi.cpp). */
private const val MIN_SECS = 5
private const val MAX_SECS = 600
private const val MARGIN_MS = 30_000L

/** ACK timeout for a run of [secs] (firmware uses [defaultSecs] when secs < [MIN_SECS]). */
internal fun headlessTimeoutMs(secs: Int, defaultSecs: Int): Long {
    val effective = if (secs < MIN_SECS) defaultSecs else secs.coerceAtMost(MAX_SECS)
    return effective * 1000L + MARGIN_MS
}

/** Responder (LLMNR/NBT-NS poisoning), headless. Needs the device joined to the Wi-Fi. Authorized use only. */
class ResponderService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    stopRunner: CommandRunner = runner,
) {
    private val op = LongOperation(scope, runner, isConnected, stopRunner)
    val status: StateFlow<String> = op.status
    val running: StateFlow<Boolean> = op.running

    /** Run `responder <secs>`; the firmware ACKs after the bounded run or an early [stop]. */
    fun start(secs: Int) {
        val runningText = "Running ${secs}s - join the device to the target Wi-Fi first (Connect tool). " +
            "Answering LLMNR/NBT-NS name queries with the device IP (authorized use)... (Stop to end early)"
        op.start("responder $secs", runningText, headlessTimeoutMs(secs, DEFAULT_SECS)) { ok, out ->
            if (ok) "Done - ${out.ifBlank { "finished" }}" else "Failed: ${out.ifBlank { "error" }}"
        }
    }

    /** End early via the generic `stop` frame; keeps the status until the final ACK. */
    fun stop() = op.requestStop("stop", null)

    /** Hard stop for disconnect / teardown. */
    fun abandon() = op.abandon()

    private companion object { const val DEFAULT_SECS = 60 }
}

/** Karma (evil-twin probe-response lure), headless. Authorized use only. */
class KarmaService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    stopRunner: CommandRunner = runner,
) {
    private val op = LongOperation(scope, runner, isConnected, stopRunner)
    val status: StateFlow<String> = op.status
    val running: StateFlow<Boolean> = op.running

    /** Run `karma <secs>`; the firmware ACKs after the bounded run or an early [stop]. */
    fun start(secs: Int) {
        val runningText = "Running ${secs}s - the device answers clients' probe requests to lure them " +
            "(authorized use). The device screen shows Karma... (Stop to end early)"
        op.start("karma $secs", runningText, headlessTimeoutMs(secs, DEFAULT_SECS)) { ok, out ->
            if (ok) "Done - ${out.ifBlank { "finished" }}" else "error: ${out.ifBlank { "failed" }}"
        }
    }

    /** End early via the generic `stop` frame; keeps the status until the final ACK. */
    fun stop() = op.requestStop("stop", null)

    /** Hard stop for disconnect / teardown. */
    fun abandon() = op.abandon()

    private companion object { const val DEFAULT_SECS = 60 }
}

/** Brucegotchi (Pwnagotchi parity): headless WPA-handshake collector. Authorized use only. */
class BrucegotchiService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    stopRunner: CommandRunner = runner,
) {
    private val op = LongOperation(scope, runner, isConnected, stopRunner)
    val status: StateFlow<String> = op.status
    val running: StateFlow<Boolean> = op.running

    /** Run `brucegotchi <secs>`; the firmware ACKs after the bounded run or an early [stop]. */
    fun start(secs: Int) {
        val runningText = "Running ${secs}s - the device screen shows the gotchi, handshakes save to " +
            "/BrucePCAP/handshakes... (Stop to end early)"
        op.start("brucegotchi $secs", runningText, headlessTimeoutMs(secs, DEFAULT_SECS)) { ok, out ->
            if (ok) "Done - $out" else "error: ${out.ifBlank { "failed" }}"
        }
    }

    /** End early via the dedicated `brucegotchi_stop` frame, showing "Stopping..." until the ACK. */
    fun stop() = op.requestStop("brucegotchi_stop", "Stopping...")

    /** Hard stop for disconnect / teardown. */
    fun abandon() = op.abandon()

    private companion object { const val DEFAULT_SECS = 30 }
}
