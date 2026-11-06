package app.brucia.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ordering for the global Stop. While a blocking device operation runs (lora_recv, script_run,
 * responder/karma/brucegotchi, or Remote owning USB) the firmware reads frames only through
 * companionPollStop, which takes ANY `stop`/`*_stop` CMD as the end of that operation without
 * answering it. Sending every service's stop at once could let another service's stop (e.g.
 * `evil_portal_stop`) end the blocking operation and be lost, its app command then waiting for
 * its timeout. So the blocking operations are ended first through their own stop paths, and the
 * remaining stops ([rest]) go out only once none of them is still active, or after [settleMs]
 * (older firmware that ignores the stop; later stops then get an answer, e.g. 409 busy).
 */
class StopAllSequence(
    private val blocking: List<Blocking>,
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
    private val onError: (String, Exception) -> Unit = { _, _ -> },
) {
    /** [requestStop] sends this operation's own stop but keeps waiting for its reply, so [active] clears only when it ended. */
    class Blocking(val name: String, val active: () -> Boolean, val requestStop: () -> Unit)

    private var pending: Job? = null

    /**
     * Run the global Stop. With no blocking operation active, [rest] runs at once and null is
     * returned. Otherwise their stops are requested now and [rest] runs later in [scope]; a
     * repeated call while that wait is pending does nothing more.
     */
    @Synchronized fun run(scope: CoroutineScope, rest: () -> Unit): Job? {
        if (pending?.isActive == true) return pending
        val active = blocking.filter { it.active() }
        if (active.isEmpty()) { rest(); return null }
        active.forEach {
            try { it.requestStop() } catch (e: Exception) { onError(it.name, e) }
        }
        return scope.launch {
            withTimeoutOrNull(settleMs) { while (blocking.any { it.active() }) delay(pollMs) }
            rest()
        }.also { pending = it }
    }

    /** Drop a pending [rest] (link lost: its stops must not reach a replacement device). */
    @Synchronized fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        /** Covers a polled stop plus its reply, and the Remote -> companion re-handshake (~1.5-3 s). */
        const val SETTLE_MS = 8_000L
        const val POLL_MS = 20L
    }
}
