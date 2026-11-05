package app.brucia.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.json.JSONObject

/**
 * Single-owner device command over a [CommandRunner]: every [start] or [stop] supersedes the
 * in-flight command, and a generation token keeps a superseded or stopped command from
 * touching [status] or [busy] afterwards. Services supply their own texts and local checks.
 */
class DeviceOperation(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val isConnected: () -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    /** True only while the current command awaits its reply. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null

    /**
     * Supersede the in-flight command, then send [cmd] unless not connected or [precheck] returns
     * a rejection text. [running] shows while waiting (null keeps the status); [result] maps
     * (ok, out) to the final text (null keeps the status, busy still clears), a runner exception
     * arriving as (false, message or ""). [timeoutMs] overrides the instance timeout for this
     * command only.
     */
    fun start(
        cmd: String,
        running: String?,
        precheck: () -> String? = { null },
        timeoutMs: Long = this.timeoutMs,
        result: (Boolean, String) -> String?,
    ) {
        launchOwned(running, precheck, { runner.run(cmd, timeoutMs) }, { e -> false to (e.message ?: "") }) { (ok, out) ->
            result(ok, out)
        }
    }

    /**
     * [start] for a command whose reply data object matters: same ownership, checks and texts,
     * but [result] receives (ok, data); a runner exception arrives as (false, {"msg": message}).
     */
    fun startJson(
        json: JsonCommandRunner,
        cmd: String,
        running: String?,
        precheck: () -> String? = { null },
        timeoutMs: Long = this.timeoutMs,
        result: (Boolean, JSONObject?) -> String?,
    ) {
        val onError: (Exception) -> Pair<Boolean, JSONObject?> = { e -> false to JSONObject().put("msg", e.message ?: "") }
        launchOwned(running, precheck, { json.runJson(cmd, timeoutMs) }, onError) { (ok, data) ->
            result(ok, data)
        }
    }

    private fun <T> launchOwned(
        running: String?,
        precheck: () -> String?,
        call: suspend () -> T,
        onError: (Exception) -> T,
        result: (T) -> String?,
    ) {
        synchronized(lock) {
            val gen = supersede()
            if (!scope.isActive) return publish(gen, "Stopped.")
            val rejection = if (!isConnected()) NOT_CONNECTED else precheck()
            if (rejection != null) return publish(gen, rejection)
            publish(gen, running, busy = true)
            job = scope.launch {
                val reply = try {
                    call()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
                currentCoroutineContext().ensureActive()
                // Check before invoking the mapper: it may update feature state or chain a command.
                if (synchronized(lock) { gen != generation }) return@launch
                try {
                    publish(gen, result(reply))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    publish(gen, "Operation failed: ${e.message}")
                }
            }.also { runningJob ->
                runningJob.invokeOnCompletion { cause ->
                    if (cause != null) publish(gen, if (cause is CancellationException) "Stopped." else "Operation failed: ${cause.message}")
                }
            }
        }
    }

    /** Cancel the in-flight command, if any; its late reply is ignored. */
    fun stop() {
        synchronized(lock) {
            val running = _busy.value
            val gen = supersede()
            if (running) publish(gen, "Stopped.")
        }
    }

    /** Show a local [text] (no command, no connection check), superseding any pending reply. */
    fun show(text: String) {
        synchronized(lock) { publish(supersede(), text) }
    }

    /**
     * Show a late, unsolicited [text] (e.g. a device EVENT) only while no command awaits its
     * reply, so a notification never cancels a pending wait. Returns false when skipped.
     */
    fun showIfIdle(text: String): Boolean = synchronized(lock) {
        if (_busy.value) return false
        publish(supersede(), text)
        true
    }

    /** Clear [status] when idle; a running command keeps its text and its pending reply. */
    fun clearStatus(): Boolean = synchronized(lock) {
        if (_busy.value) return false
        _status.value = ""
        true
    }

    /** Cancel the current command and return the token for the next one. */
    private fun supersede(): Long {
        ++generation // invalidate completion handlers before cancel() can invoke them inline
        job?.cancel()
        job = null
        return generation
    }

    /** Apply [text] (null keeps it) and [busy] only while [gen] still owns the operation. */
    private fun publish(gen: Long, text: String?, busy: Boolean = false) {
        synchronized(lock) {
            if (gen != generation) return
            if (text != null) _status.value = text
            _busy.value = busy
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val NOT_CONNECTED = "Connect the device over USB first."
    }
}
