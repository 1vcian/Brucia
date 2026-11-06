package app.brucia.ir

import app.brucia.session.CommandRunner
import app.brucia.session.CommandTasks
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * IR power spam (`ir_power_spam <secs>`): the device blasts every power table - both regions
 * (NA + EU) and the universal parsed + raw codes - and repeats the whole set until the window ends
 * or a stop is polled, so it covers all device types, not one region's TVs. One single-owner
 * [DeviceOperation]; the firmware repeats internally, so this is one bounded command (up to
 * [MAX_SECS]). [requestStop] sends the polled `ir_power_spam_stop` and keeps waiting for the ACK,
 * so Stop ends the loop at once (the firmware polls the link between codes) instead of after the
 * whole window. Authorized use only. Mirrors [app.brucia.monitor.JamDetectService].
 */
class IrPowerSpamService(
    scope: CoroutineScope,
    runner: CommandRunner,
    private val isConnected: () -> Boolean,
    /** Carries `ir_power_spam_stop`: the firmware polls it mid-run and never ACKs it there. */
    private val stopRunner: CommandRunner = runner,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val stops = CommandTasks(scope)
    private val lock = Any()
    /** True from [requestStop] until its stop frame has been written; blocks a restart (F32). */
    private var stopInFlight = false
    val status: StateFlow<String> = op.status

    /** True while a spam window is running. */
    val busy: StateFlow<Boolean> = op.busy

    /**
     * Blast every power table for [secs], repeating; ignored if already running. [secs] below 1 uses
     * [DEFAULT_SECS] and above [MAX_SECS] is clamped, mirroring the firmware's own bound.
     */
    fun run(secs: Int = DEFAULT_SECS) {
        // Refuse while running, or while a previous stop is still unresolved: otherwise that stop
        // could be written during this new window and interrupt it (F32).
        synchronized(lock) { if (op.busy.value || stopInFlight) return }
        val s = if (secs < 1) DEFAULT_SECS else secs.coerceAtMost(MAX_SECS)
        op.start("ir_power_spam $s", "Blasting power codes (${s}s, Stop to end)...", timeoutMs = timeoutMsFor(s)) { ok, out ->
            out.ifBlank { if (ok) "IR power spam done." else "IR power spam failed." }
        }
    }

    /**
     * End a running spam on the device: send the polled `ir_power_spam_stop` but keep waiting, so
     * [busy] clears with the device's final "... (stopped)" reply. Nothing running or no link:
     * nothing is sent.
     */
    fun requestStop() {
        synchronized(lock) {
            if (!op.busy.value || !isConnected() || stopInFlight) return
            stopInFlight = true
        }
        // Resolved (fence lifted) once the stop frame is written: the stop runner returns after the write.
        stops.run(stopRunner, STOP_CMD, STOP_TIMEOUT_MS) { _, _ -> synchronized(lock) { stopInFlight = false } }
    }

    /** Local stop (disconnect, or after [requestStop]): cancel the wait and any unsent stop, drop late replies. */
    fun stop() {
        synchronized(lock) { stopInFlight = false }
        stops.cancel()
        op.stop()
    }

    companion object {
        const val DEFAULT_SECS = 30
        const val MAX_SECS = 120
        const val STOP_CMD = "ir_power_spam_stop"
        /** Bounds only the fire-and-forget launch: the firmware polls the stop and never ACKs it. */
        const val STOP_TIMEOUT_MS = 10_000L
        /** The firmware bounds the run at <secs>; margin covers the final ACK. */
        fun timeoutMsFor(secs: Int): Long = secs.coerceIn(1, MAX_SECS).toLong() * 1000L + 10_000L
        val TIMEOUT_MS = timeoutMsFor(MAX_SECS)
    }
}
