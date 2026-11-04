package app.brucia.monitor

import app.brucia.session.CommandRunner
import app.brucia.wardrive.WidsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * WIDS monitor: back-to-back jam_detect windows (802.11 monitor mode, ESP-only) that log
 * deauth/disassoc floods with a phone timestamp and raise an ALERT when a window's deauth count
 * reaches the threshold. Defensive / authorized use.
 *
 * Bug fix over the VM loop: the VM's `while (true)` never ends on its own, so after a disconnect
 * every jam_detect fails immediately with "Not connected." and the loop spins with no delay. This
 * service re-checks the link at the top of each window and ends the loop (clearing [active]) when
 * [isConnected] or [linkOpen] is false, with the distinct status [LINK_CLOSED]; [stop] uses
 * [STOPPED]. A superseded run (its own [Job]) never writes state the next run owns, so only the
 * owning run touches [status]/[events] after a restart.
 *
 * WIDS is not a [app.brucia.session.BurstLoop], so it cannot join a BurstLoopRegistry (register is
 * internal, typed to BurstLoop). The coordinator must call [stop] from stopAllBurstLoops, so a
 * disconnect or an exclusive stream seizing the link also halts WIDS.
 */
class WidsService(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val isConnected: () -> Boolean,
    private val linkOpen: () -> Boolean,
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _events = MutableStateFlow<List<WidsEvent>>(emptyList())
    val events: StateFlow<List<WidsEvent>> = _events.asStateFlow()
    private val lock = Any()
    private var job: Job? = null

    /** Start the monitor: a window every [windowSecs] s, ALERT when deauths >= [threshold].
     *  Ignored while already [active]; clears [events] on start. */
    fun start(windowSecs: Int = 15, threshold: Int = 20) {
        synchronized(lock) {
            if (!isConnected()) { _status.value = NOT_CONNECTED; return }
            if (_active.value) return
            if (!scope.isActive) { _status.value = STOPPED; return }
            if (windowSecs !in 1..120 || threshold < 1) {
                _status.value = "Window must be 1–120 seconds and threshold positive."
                return
            }
            _active.value = true
            _events.value = emptyList()
            _status.value = windowStatus(1, windowSecs)
            val next = scope.launch(start = CoroutineStart.LAZY) {
                val myJob = coroutineContext[Job]
                var windows = 0
                try {
                    while (isActive && _active.value && isConnected() && linkOpen()) {
                        publish(myJob) { windowStatus(windows + 1, windowSecs) }
                        // A thrown runner error (not cancellation) is a failed window: keep monitoring, as the VM.
                        val (ok, out) = try {
                            runner.run("jam_detect $windowSecs", (windowSecs + 8) * 1000L)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false to (e.message?.takeIf { it.isNotBlank() } ?: JAM_FAILED)
                        }
                        windows++
                        if (ok) {
                            val deauth = count("deauth=", out)
                            val total = count("total=", out)
                            val alert = deauth >= threshold
                            if (alert || deauth > 0) appendEvent(myJob, WidsEvent(System.currentTimeMillis(), deauth, total, alert))
                            val w = windows
                            publish(myJob) {
                                if (alert) "ALERT: deauth flood ($deauth in ${windowSecs}s)"
                                else "Clear (window $w, deauth=$deauth)"
                            }
                        } else {
                            publish(myJob) { out.ifBlank { JAM_FAILED } }
                            // An instant firmware rejection would otherwise re-send back to back.
                            delay(FAILURE_BACKOFF_MS)
                        }
                        yield() // checkpoint; also lets observers see this result before the next window overwrites it
                    }
                } finally {
                    // Natural end = link closed; stop() cancels and is guarded out here, keeping its own STOPPED.
                    finish(myJob, if (isActive) LINK_CLOSED else STOPPED)
                }
            }
            job = next
            next.invokeOnCompletion { finish(next, STOPPED) }
            next.start()
        }
    }

    /** Stop the monitor; late replies from the superseded run are ignored. */
    fun stop() = synchronized(lock) {
        val previous = job
        job = null
        previous?.cancel()
        _active.value = false
        _status.value = STOPPED
    }

    /** Clear the logged events. */
    fun clearEvents() { _events.value = emptyList() }

    /** Apply the end state only while [myJob] still owns the loop (a restart supersedes it). */
    private fun finish(myJob: Job?, end: String) = synchronized(lock) {
        if (job === myJob) { _active.value = false; job = null; _status.value = end }
    }

    /** Write [text] only while [myJob] still owns the loop. */
    private inline fun publish(myJob: Job?, text: () -> String) =
        synchronized(lock) { if (job === myJob) _status.value = text() }

    /** Append [event] (last 100) only while [myJob] still owns the loop. */
    private fun appendEvent(myJob: Job?, event: WidsEvent) =
        synchronized(lock) { if (job === myJob) _events.value = (_events.value + event).takeLast(100) }

    private fun windowStatus(window: Int, windowSecs: Int) = "Monitoring (window $window, ${windowSecs}s)..."

    /** Parses "<key><digits>" out of a jam_detect reply, 0 when absent (matches the VM regex). */
    private fun count(key: String, out: String): Int =
        Regex(Regex.escape(key) + "(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        const val NOT_CONNECTED = "Connect the device over USB first."
        const val STOPPED = "Monitor stopped."
        const val LINK_CLOSED = "Monitor stopped (link closed)."
        const val JAM_FAILED = "jam_detect failed"
        const val FAILURE_BACKOFF_MS = 1_000L
    }
}
