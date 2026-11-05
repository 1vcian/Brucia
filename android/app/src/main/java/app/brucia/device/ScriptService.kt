package app.brucia.device

import app.brucia.session.CommandRunner
import app.brucia.session.CommandTasks
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Headless JavaScript run on the device (script_run <src>) on a single-owner [DeviceOperation].
 * The firmware's size limit is checked locally so an oversized source is never sent.
 *
 * [stop] ends the run on the device: it sends `script_stop` through [stopRunner] (the firmware
 * consumes it while script_run waits and never ACKs it, so callers pass a direct, unawaited sender)
 * and keeps waiting for the script_run reply, which then shows the true outcome (`script stopped`,
 * or the output when the script ended first). One stop frame per run at most; nothing is sent when
 * no run waits. A new [run] is refused while a run waits or its stop frame is still being written,
 * so the stop can never reach a later script. [abandon] (disconnect) drops the wait without a frame.
 */
class ScriptService(
    private val scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    private val stopRunner: CommandRunner = runner,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val stops = CommandTasks(scope)
    /** Captured script output, progress or error text. */
    val output: StateFlow<String> = op.status
    /** True while a script_run awaits its reply. */
    val busy: StateFlow<Boolean> = op.busy
    private val _stopping = MutableStateFlow(false)
    /** True from [stop] until both the run's reply arrived and the stop frame was written. */
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val lock = Any()
    private var runSeq = 0L           // the run the current wait belongs to
    private var stopSentFor = -1L     // the run a stop frame was sent for
    private var stopWriting = false   // the stop frame's send has not returned yet
    private var graceJob: Job? = null

    /**
     * Run [src], sent as given (the firmware trims it). Returns false, sending nothing and keeping
     * the output, while a script still runs or is being stopped (a local rejection such as "not
     * connected" or "script too large" returns true and shows on [output]).
     */
    fun run(src: String): Boolean = synchronized(lock) {
        if (op.busy.value || _stopping.value) return false
        val id = ++runSeq
        op.start("script_run $src", RUNNING, { precheck(src) }) { ok, out -> finish(id, ok, out) }
        true
    }

    /**
     * Stop the running script on the device. Returns true when the stop frame was sent; false (no
     * frame) when no script runs, the reply already arrived, or a stop was already sent for it.
     */
    fun stop(): Boolean = synchronized(lock) {
        if (!op.busy.value || stopSentFor == runSeq) return false
        val id = runSeq
        stopSentFor = id
        stopWriting = true
        _stopping.value = true
        op.updateRunning(STOPPING)
        stops.run(stopRunner, STOP_CMD, STOP_SEND_TIMEOUT_MS) { _, _ ->
            synchronized(lock) {
                stopWriting = false
                if (!op.busy.value) _stopping.value = false
            }
        }
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(STOP_GRACE_MS)
            synchronized(lock) { if (id == runSeq) op.updateRunning(STOP_SLOW) }
        }
        true
    }

    /** Disconnect / teardown: drop the wait and any unsent stop; a late reply is ignored. */
    fun abandon() {
        synchronized(lock) {
            stops.cancel()
            graceJob?.cancel()
            graceJob = null
            stopWriting = false
            _stopping.value = false
            op.stop(ABANDONED)
        }
    }

    /**
     * Clear the output when idle. While a script runs this is a no-op: clearing must not supersede
     * the run, whose reply would be lost while the device keeps executing.
     */
    fun clear() { op.clearStatus() }

    private fun finish(id: Long, ok: Boolean, out: String): String = synchronized(lock) {
        val stopSent = stopSentFor == id
        if (id == runSeq) {
            graceJob?.cancel()
            graceJob = null
            if (!stopWriting) _stopping.value = false
        }
        resultText(ok, out, stopSent)
    }

    companion object {
        /** Firmware bounds execution at 15 s (20 s wall clock); leave margin for the reply. */
        const val TIMEOUT_MS = 25_000L
        /** Firmware limit on the trimmed source (String.length(), i.e. UTF-8 bytes). */
        const val MAX_SOURCE_BYTES = 6000
        const val STOP_CMD = "script_stop"
        /** Bounds only the fire-and-forget write of the stop frame (it is never ACKed). */
        const val STOP_SEND_TIMEOUT_MS = 10_000L
        /** The firmware ends a stopped script within a few polls; after this, say it is slow. */
        const val STOP_GRACE_MS = 3_000L
        const val RUNNING = "Running..."
        const val STOPPING = "Stopping..."
        const val STOP_SLOW = "Stop sent; still waiting for the device to end the script " +
            "(firmware that does not read stops during a script ends it at its 15 s bound)."
        const val STOPPED = "Script stopped."
        const val ABANDONED = "Disconnected before the script ended; its output is lost."
        const val ENDED_FIRST = "(The script ended on its own: the stop came too late, or this " +
            "firmware does not read stops during a script.)"

        /** Local rejection text, or null when [src] may be sent. */
        fun precheck(src: String): String? = when {
            src.isBlank() -> "Nothing to run."
            src.trim().toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES -> "script too large"
            else -> null
        }

        /** The text a script_run reply shows; [stopSent] when the app asked this run to stop. */
        fun resultText(ok: Boolean, out: String, stopSent: Boolean): String = when {
            !ok && out == "script stopped" -> STOPPED
            ok && stopSent -> out.ifBlank { "(no output)" } + "\n" + ENDED_FIRST
            ok -> out.ifBlank { "(no output)" }
            stopSent && out == "script timeout" -> "script timeout\n$ENDED_FIRST"
            else -> out.ifBlank { "Script failed." }
        }
    }
}
