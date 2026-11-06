package app.brucia.subghz

import app.brucia.session.CommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams a planned De Bruijn sweep ([DeBruijnTx.Plan.Ready]) to the device as a sequence of
 * `subghz_tx_raw` bursts, one frame at a time over a [CommandRunner], awaiting each ACK before the
 * next. Mirrors the other single-owner feature services: honest progress ("sweep 3/12..."), a real
 * per-burst result (a failed or rejected burst stops the run and is reported), and an honest final
 * summary. [stop] (user Stop or disconnect via the ViewModel's service lifecycle) halts between
 * bursts and sends nothing further.
 *
 * Capability/companion gating is NOT duplicated here: the command runner routes through the
 * session preflight, which requires CC1101 for any `subghz_*` verb (CommandCapabilities), so a
 * board without the radio fails the first burst with the firmware's own reason and the run stops.
 * This service adds no new transmit capability beyond the existing `subghz_tx_raw` command.
 */
class DeBruijnTxService(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val isConnected: () -> Boolean,
) {
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _active = MutableStateFlow(false)
    /** True while the sweep is streaming. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null
    // True only while a `subghz_tx_raw` burst has been dispatched to the device and we are awaiting
    // its ACK: that burst is already on air and we cannot recall it. Guarded by [lock]. [stop] reads
    // it to tell "no further bursts will be sent" (always guaranteed) apart from "a burst already
    // sent is stopped" (never guaranteed) so the status never claims the sweep is halted on-device
    // when only the queue of later bursts was cancelled.
    private var burstInFlight = false

    /**
     * Start streaming [plan] (its frequency shown as [freqLabel], e.g. "433.92 MHz"). Supersedes any
     * run in flight. Sends nothing and shows [NOT_CONNECTED] when not connected. Returns the
     * immediate status line (the running text, or the not-connected reason) so the caller can echo
     * it; progress and the final summary then arrive through [status].
     */
    fun start(plan: DeBruijnTx.Plan.Ready, freqLabel: String): String {
        synchronized(lock) {
            val gen = supersede()
            if (!isConnected()) return publish(gen, NOT_CONNECTED, active = false)
            val total = plan.commands.size
            // Each burst's ACK follows the whole on-air time of the largest chunk; give headroom.
            val timeoutMs = plan.maxChunkOnAirUs / 1000L + PER_BURST_HEADROOM_MS
            val running = running(0, total, freqLabel)
            publish(gen, running, active = true)
            job = scope.launch {
                var done = 0
                var failure: String? = null
                try {
                    for (cmd in plan.commands) {
                        currentCoroutineContext().ensureActive()
                        if (!isActive) break
                        synchronized(lock) { if (owned(gen)) burstInFlight = true }
                        val (ok, out) = try {
                            runner.run(cmd, timeoutMs)
                        } finally {
                            synchronized(lock) { if (owned(gen)) burstInFlight = false }
                        }
                        currentCoroutineContext().ensureActive()
                        if (owned(gen).not()) return@launch
                        if (!ok) { failure = out.ifBlank { "no response" }; break }
                        done++
                        publish(gen, running(done, total, freqLabel), active = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message?.takeIf { it.isNotBlank() } ?: "error"
                }
                if (owned(gen).not()) return@launch
                val summary = when {
                    failure != null -> "De Bruijn sweep stopped at burst ${done + 1}/$total on $freqLabel: $failure"
                    else -> "De Bruijn B(2,${plan.order}) sweep sent: $total/$total bursts on $freqLabel " +
                        "(${plan.passes} pass${if (plan.passes == 1) "" else "es"}, ${plan.linearizedBits} bits/pass)."
                }
                publish(gen, summary, active = false)
            }.also { j ->
                j.invokeOnCompletion { cause ->
                    if (cause is CancellationException) publish(gen, STOPPED, active = false)
                }
            }
            return running
        }
    }

    /**
     * Halt a running sweep (user Stop / disconnect / Remote hand-over): no further burst is sent and
     * a late reply is ignored. We can always guarantee the *remaining* bursts are cancelled, but a
     * burst already dispatched to the device ([burstInFlight]) is on air and cannot be recalled, so
     * with no caller [text] the status says so honestly instead of claiming the sweep stopped. A
     * caller that already phrases the caveat (the Remote hand-over) passes its own [text].
     */
    fun stop(text: String? = null) {
        synchronized(lock) {
            val wasActive = _active.value
            val inFlight = burstInFlight
            val gen = supersede()
            if (wasActive) publish(gen, text ?: if (inFlight) STOPPED_IN_FLIGHT else STOPPED, active = false)
        }
    }

    /** Show a local [reason] (e.g. a planner rejection) without sending anything. */
    fun show(reason: String) {
        synchronized(lock) { publish(supersede(), reason, active = false) }
    }

    private fun running(done: Int, total: Int, freqLabel: String) =
        "Transmitting De Bruijn sweep: burst $done/$total on $freqLabel..."

    private fun owned(gen: Long): Boolean = synchronized(lock) { gen == generation }

    private fun supersede(): Long {
        ++generation
        job?.cancel()
        job = null
        burstInFlight = false
        return generation
    }

    private fun publish(gen: Long, text: String, active: Boolean): String {
        synchronized(lock) {
            if (gen == generation) {
                _status.value = text
                _active.value = active
            }
        }
        return text
    }

    companion object {
        const val NOT_CONNECTED = "Connect the device over USB first."
        const val STOPPED = "De Bruijn sweep stopped."
        /** Stopped while a burst was already on air: later bursts cancelled, that one cannot be recalled. */
        const val STOPPED_IN_FLIGHT =
            "De Bruijn sweep stopped; no further bursts will be sent, but the burst already sent may still be transmitting on the device."
        /** Slack added to a burst's on-air time for radio bring-up/teardown and the ACK round-trip. */
        const val PER_BURST_HEADROOM_MS = 6_000L
    }
}
