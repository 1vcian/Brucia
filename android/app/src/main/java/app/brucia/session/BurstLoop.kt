package app.brucia.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks every [BurstLoop] built against it so one call can halt them all, mirroring the
 * ViewModel's stopAllBurstLoops (disconnect, or when an exclusive stream needs the link).
 * Loops register at construction, so any started later are stopped too.
 */
class BurstLoopRegistry {
    private val loops = CopyOnWriteArrayList<BurstLoop>()

    internal fun register(loop: BurstLoop) {
        loops += loop
    }

    /** Stop every registered loop; [silent] keeps each status line (see [BurstLoop.stop]). */
    fun stopAll(silent: Boolean) {
        loops.forEach { it.stop(silent) }
    }
}

/**
 * One abortable short-burst loop (BLE/Wi-Fi spam, deauth, NRF24 ops, Sub-GHz/IR jammer, ARP
 * spoof, iBeacon). Instead of a single long blocking command that froze the device and link, it
 * fires SHORT bounded bursts back to back over a [CommandRunner], awaiting each reply before the
 * next, so bursts stay sequential and Stop lands within one burst. [active] gates the loop and
 * drives the Start/Stop toggle; [status] carries the user-facing line. A superseded or stopped
 * run (identified by its own [Job]) never touches state the next run owns.
 *
 * Connection, input and exclusive-stream checks stay in the caller: it validates and, on a
 * rejection, calls [show] before deciding not to [start]. This engine has no precheck.
 * [statusLine] may be shared by loops that drive one UI line (e.g. NRF24 scan and sniff); each
 * loop only replaces its own running text.
 */
class BurstLoop(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val linkOpen: () -> Boolean,
    registry: BurstLoopRegistry,
    statusLine: MutableStateFlow<String> = MutableStateFlow(""),
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _status = statusLine
    val status: StateFlow<String> = _status.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var runningText = ""
    private var stoppedText = STOPPED
    private var onEnd: () -> Unit = {}

    init {
        registry.register(this)
    }

    /**
     * Stop any running loop silently (never two at once), then run [cmd]'s bursts while [active]
     * and [linkOpen]. [running] shows while looping. Each burst is `runner.run(cmd(), timeoutMs)`:
     * a failure with non-blank out ends the loop and shows that text (the VM's onReject); a blank
     * failure continues. A runner exception other than cancellation ends the loop with its message
     * as status, or [FAILED] when blank. When the loop ends under its own ownership (link closed,
     * rejection or cancellation), [active] clears, [status] becomes [stoppedText] if it still
     * equals [running], and [onEnd] runs (not after [stop], whose caller owns that cleanup).
     */
    fun start(
        running: String,
        cmd: () -> String,
        timeoutMs: Long,
        stoppedText: String = STOPPED,
        onEnd: () -> Unit = {},
    ) {
        synchronized(lock) {
            stop(silent = true)
            runningText = running
            this.stoppedText = stoppedText
            this.onEnd = onEnd
            _active.value = true
            _status.value = running
            job = scope.launch {
                val myJob = coroutineContext[Job]
                var rejection: String? = null
                try {
                    while (isActive && _active.value && linkOpen()) {
                        val (ok, out) = try {
                            runner.run(cmd(), timeoutMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            rejection = e.message?.takeIf { it.isNotBlank() } ?: FAILED
                            break
                        }
                        if (!ok && out.isNotBlank()) { rejection = out; break }
                    }
                } finally {
                    finish(myJob, rejection)
                }
            }
        }
    }

    /**
     * Cancel the running loop and clear [active]. [silent] keeps the status line (used when
     * restarting); otherwise [stoppedText] replaces the status only while the running text still
     * shows, so a rejection text stays.
     */
    fun stop(silent: Boolean = false) {
        synchronized(lock) {
            job?.cancel()
            job = null
            _active.value = false
            if (!silent && _status.value == runningText) _status.value = stoppedText
        }
    }

    /** Show a local [text] (no command): the caller's own line when it rejects before [start]. */
    fun show(text: String) {
        synchronized(lock) { _status.value = text }
    }

    /** Apply the end state only while [myJob] still owns the loop (a restart supersedes it). */
    private fun finish(myJob: Job?, rejection: String?) {
        synchronized(lock) {
            if (job !== myJob) return
            _active.value = false
            when {
                rejection != null -> _status.value = rejection
                _status.value == runningText -> _status.value = stoppedText
            }
            onEnd()
        }
    }

    companion object {
        const val STOPPED = "Stopped."
        const val FAILED = "Failed."
    }
}
