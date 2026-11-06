package app.brucia.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    /** True while any registered loop runs. */
    val anyActive: Boolean get() = loops.any { it.active.value }

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
 *
 * With [stopCmd] (a burst the firmware polls a stop for, e.g. `nrf24_stop`), [stop] during a burst
 * sends it over [stopRunner] and lets that burst's reply arrive instead of cancelling the wait:
 * the device ends the window at once, and [draining] stays true until it has answered, so the
 * global Stop can send other stops only after this one was consumed. A later [start] waits for it.
 */
class BurstLoop(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val linkOpen: () -> Boolean,
    registry: BurstLoopRegistry,
    statusLine: MutableStateFlow<String> = MutableStateFlow(""),
    private val stopCmd: String? = null,
    private val stopRunner: CommandRunner? = null,
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
    /** The run whose burst awaits its reply right now (null between bursts). */
    private var inFlight: Job? = null
    /** A stopped run still owed its interrupted burst's reply (see [draining]). */
    private var drainJob: Job? = null
    private val stops = CommandTasks(scope)
    private val _draining = MutableStateFlow(false)
    /** True after a [stop] that sent [stopCmd] mid-burst, until the device answered that burst. */
    val draining: StateFlow<Boolean> = _draining.asStateFlow()
    /** The stopped run still owed its burst's reply, for another loop sharing the device window to wait on. */
    val pendingDrain: Job? get() = synchronized(lock) { drainJob }

    init {
        registry.register(this)
    }

    /**
     * Stop any running loop silently (never two at once), then run [cmd]'s bursts while [active]
     * and [linkOpen]. [running] shows while looping. Each burst is `runner.run(cmd(), timeoutMs)`:
     * any failure ends the loop (a blank failure reports [FAILED]).
     * A runner exception other than cancellation ends the loop with its message
     * as status, or [FAILED] when blank. When the loop ends under its own ownership (link closed,
     * rejection or cancellation), [active] clears, [status] becomes [stoppedText] if it still
     * equals [running], and [onEnd] runs (not after [stop], whose caller owns that cleanup).
     * [progress], when given, maps each successful burst reply to a new running text (null keeps
     * the current one); it applies only while this run owns the loop and replaces the status only
     * while the previous running text still shows, so Stop and a rejection behave as before.
     */
    fun start(
        running: String,
        cmd: () -> String,
        timeoutMs: Long,
        stoppedText: String = STOPPED,
        onEnd: () -> Unit = {},
        progress: ((String) -> String?)? = null,
        after: Job? = null,
    ) {
        synchronized(lock) {
            stop(silent = true)
            runningText = running
            this.stoppedText = stoppedText
            this.onEnd = onEnd
            if (!scope.isActive) {
                _status.value = stoppedText
                onEnd()
                return
            }
            _active.value = true
            _status.value = running
            val pendingDrain = drainJob
            val next = scope.launch(start = CoroutineStart.LAZY) {
                val myJob = coroutineContext[Job]
                var rejection: String? = null
                try {
                    // A stopped burst still running on the device (this loop's, or [after]) would answer this one "busy".
                    pendingDrain?.join()
                    after?.join()
                    // Owner check: a burst drained after [stop] must not continue for a newer run.
                    while (isActive && _active.value && synchronized(lock) { job === myJob } && linkOpen()) {
                        synchronized(lock) { if (job === myJob) inFlight = myJob }
                        val (ok, out) = try {
                            runner.run(cmd(), timeoutMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            rejection = e.message?.takeIf { it.isNotBlank() } ?: FAILED
                            break
                        } finally {
                            synchronized(lock) { if (inFlight === myJob) inFlight = null }
                        }
                        if (!ok) { rejection = out.ifBlank { FAILED }; break }
                        progress?.invoke(out)?.let { advance(myJob, it) }
                    }
                } finally {
                    finish(myJob, rejection)
                }
            }
            job = next
            next.invokeOnCompletion { finish(next, null) }
            next.start()
        }
    }

    /**
     * Cancel the running loop and clear [active]. [silent] keeps the status line (used when
     * restarting); otherwise [stoppedText] replaces the status only while the running text still
     * shows, so a rejection text stays.
     */
    fun stop(silent: Boolean = false) {
        synchronized(lock) {
            val previous = job
            job = null
            val sendStop = stopCmd != null && stopRunner != null && previous != null &&
                inFlight === previous && linkOpen()
            if (sendStop) {
                // Leave the burst's wait in place: its reply proves the device left the window.
                stops.run(stopRunner!!, stopCmd!!, STOP_TIMEOUT_MS)
                drainJob = previous
                _draining.value = true
                previous!!.invokeOnCompletion {
                    synchronized(lock) {
                        if (drainJob === previous) { drainJob = null; _draining.value = false }
                    }
                }
            } else previous?.cancel()
            _active.value = false
            if (!silent && _status.value == runningText) _status.value = stoppedText
        }
    }

    /** Show a local [text] (no command): the caller's own line when it rejects before [start]. */
    fun show(text: String) {
        synchronized(lock) { _status.value = text }
    }

    /** Replace the running text after a successful burst, only while [myJob] still owns the loop. */
    private fun advance(myJob: Job?, text: String) {
        synchronized(lock) {
            if (job !== myJob || !_active.value) return
            if (_status.value == runningText) _status.value = text
            runningText = text
        }
    }

    /** Apply the end state only while [myJob] still owns the loop (a restart supersedes it). */
    private fun finish(myJob: Job?, rejection: String?) {
        synchronized(lock) {
            if (job !== myJob) return
            job = null
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
        /** Bounds only the fire-and-forget stop launch: the firmware polls it and never ACKs it. */
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
