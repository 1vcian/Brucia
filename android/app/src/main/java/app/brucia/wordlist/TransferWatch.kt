package app.brucia.wordlist

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A dictionary transfer ran longer than its total time limit. */
class TransferTimeoutException(message: String) : IOException(message)

/**
 * Cancellation and total-time fence for one blocking dictionary transfer.
 *
 * A per-read socket timeout does not stop a trickling server, and a cancelled coroutine
 * does not interrupt a blocked `read()`. While open, a shared daemon thread polls
 * [cancelled] and the deadline every [POLL_MS]; when either fires it runs the registered
 * abort action (disconnect the connection, close the stream) so the blocked reader
 * returns. [stopped] is also checked between reads by the copy loop. [translate] maps
 * the resulting stream error to [InterruptedException] (cancel) or
 * [TransferTimeoutException] (deadline) so callers see the real reason.
 */
class TransferWatch(
    private val cancelled: () -> Boolean,
    private val totalMillis: Long,
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val deadline = clock() + TimeUnit.MILLISECONDS.toNanos(totalMillis)
    private val lock = Any()
    private var abort: (() -> Unit)? = null
    private var closed = false

    /** Why the transfer was stopped, or null while it may continue. */
    @Volatile var reason: Reason? = null
        private set

    enum class Reason { CANCELLED, TIMED_OUT }

    private val poll: ScheduledFuture<*> =
        POLLER.scheduleWithFixedDelay({ runCatching { check() } }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)

    /** True once cancelled or past the deadline; the copy loop stops at the next read. */
    fun stopped(): Boolean = check() != null

    /** Throws the cancel/deadline exception if stopped; call before committing a result. */
    fun ensureRunning() { if (stopped()) throw translate(InterruptedException("Cancelled")) }

    /** Registers what stops the current blocking IO. Runs it at once if already stopped. */
    fun onAbort(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (closed) return
            abort = action
            reason != null
        }
        if (runNow || check() != null) runCatching(action)
    }

    private fun check(): Reason? {
        reason?.let { return it }
        val now = when {
            cancelled() -> Reason.CANCELLED
            clock() - deadline >= 0 -> Reason.TIMED_OUT
            else -> return null
        }
        val action = synchronized(lock) {
            if (reason == null) reason = now
            if (closed) null else abort
        }
        action?.let { runCatching(it) }
        return reason
    }

    /** The exception to report for [error], which ended a transfer this watch may have stopped. */
    fun translate(error: Throwable): Throwable = when (reason) {
        Reason.CANCELLED -> InterruptedException("Cancelled")
        Reason.TIMED_OUT -> TransferTimeoutException(
            "Download took longer than ${TimeUnit.MILLISECONDS.toSeconds(totalMillis)} s; stopped")
        null -> error
    }

    override fun close() {
        synchronized(lock) { closed = true; abort = null }
        poll.cancel(false)
    }

    companion object {
        const val POLL_MS = 50L
        private val POLLER = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "brucia-transfer-watch").apply { isDaemon = true }
        }

        /**
         * Runs [block] under a watch; failures caused by cancel/deadline are reported as such.
         * [block] must call [ensureRunning] before committing a result (promotion, return).
         */
        fun <T> guard(cancelled: () -> Boolean, totalMillis: Long, clock: () -> Long,
                      block: (TransferWatch) -> T): T = TransferWatch(cancelled, totalMillis, clock).use { watch ->
            try {
                block(watch)
            } catch (e: Exception) {
                throw watch.translate(e)
            }
        }
    }
}
