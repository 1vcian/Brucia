package app.brucia.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One long, bounded, one-shot device command (responder / karma / brucegotchi) that the firmware
 * can end early on a separate stop frame it polls mid-run. Built on [DeviceOperation] (composed,
 * not modified): [start] keeps single-owner supersede semantics while [running] mirrors the wait
 * for the final ACK. [requestStop] sends a stop command alongside WITHOUT cancelling that wait,
 * so the firmware's final ACK still flows through the original [start]'s result mapper.
 * [status] mirrors the operation but may show a transient stopping text until that ACK lands.
 * [stopRunner] carries the stop frame; the firmware polls it and never ACKs it, so callers pass
 * a direct sender instead of a runner that would log a pending command and time it out.
 */
class LongOperation(
    private val scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    private val stopRunner: CommandRunner = runner,
) {
    private val op = DeviceOperation(scope, runner, isConnected, DEFAULT_TIMEOUT_MS)

    private val _status = MutableStateFlow("")
    /** Latest operation text, overlaid by a stopping text until the final ACK replaces it. */
    val status: StateFlow<String> = _status.asStateFlow()

    /** True only while the command awaits its final ACK (clears on reply / supersede / abandon). */
    val running: StateFlow<Boolean> = op.busy

    init {
        // Mirror the guarded operation status: a superseded or stopped command never leaks here.
        scope.launch { op.status.collect { _status.value = it } }
    }

    /**
     * Supersede the in-flight command, then send [cmd] unless not connected (then the status shows
     * [DeviceOperation.NOT_CONNECTED] and nothing is sent). [runningText] shows while waiting;
     * [result] maps the final (ok, out) to the status text. [timeoutMs] must exceed the bounded run.
     */
    fun start(cmd: String, runningText: String, timeoutMs: Long, result: (Boolean, String) -> String) {
        op.start(cmd, runningText, timeoutMs = timeoutMs) { ok, out -> result(ok, out) }
    }

    /**
     * End the run early: if [running], send [stopCmd] fire-and-forget through the runner without
     * cancelling the wait, so the firmware's final ACK still maps through [start]'s result; show
     * [stoppingText] meanwhile when given. Does nothing if not running.
     */
    fun requestStop(stopCmd: String, stoppingText: String?) {
        if (!op.busy.value) return
        stoppingText?.let { _status.value = it }
        scope.launch {
            try {
                stopRunner.run(stopCmd, STOP_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fire-and-forget: the stop frame carries no status of its own.
            }
        }
    }

    /** Hard stop for disconnect / teardown: cancel the wait (DeviceOperation.stop) and drop any late reply. */
    fun abandon() = op.stop()

    companion object {
        /** Generous default; each [start] passes a timeout derived from the bounded run length. */
        const val DEFAULT_TIMEOUT_MS = 650_000L
        /** The stop frame is polled, not acked, so this only bounds the fire-and-forget launch. */
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
