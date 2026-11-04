package app.brucia.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Monotonic milliseconds; wall-clock jumps must not move ownership deadlines. */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

/**
 * One opened USB port + connection. [close] runs the closer exactly once, whichever release path
 * (operation end, job completion, error path, owner shutdown) reaches it first. The closer is
 * expected to log its own failures; an exception from it never escapes a release path.
 */
class PortLease(private val closer: () -> Unit) {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    /** True only for the call that actually released the port. */
    fun close(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        try { closer() } catch (_: Exception) { /* closer logs its own failures */ }
        return true
    }
}

/**
 * Release rule for a usb-serial-for-android port and the UsbDeviceConnection handed to its
 * `open()`, from the resolved 3.8.1 bytecode (CommonUsbSerialPort.java): `close()` cancels the read
 * request, runs `closeInt()` and then closes the connection the port was opened with (line 155);
 * `open()` refuses an already open port with "Already open" BEFORE taking the new connection
 * (lines 114-115) and, when it fails after taking it, calls `close()` itself (lines 131-133).
 * So when this caller's `open()` returned normally ([openedHere]) only the port is closed, which
 * also closes the connection; otherwise the port is not touched (on "Already open" it belongs to
 * another, live user) and the connection is closed directly; after a failed open the library may
 * have closed it already, and UsbDeviceConnection.close() is guarded by isOpen() in the android-36
 * SDK sources, so that second close is a no-op there. Exceptions are the closers' to log.
 */
internal fun releaseUsbPort(openedHere: Boolean, closePort: () -> Unit, closeConnection: () -> Unit) {
    if (openedHere) closePort() else closeConnection()
}

/**
 * Single owner of the USB serial device: at most one operation holds it at a time, either the
 * companion link ([Kind.COMPANION]) or a firmware flash ([Kind.FLASH]). Each operation gets a
 * token; the port opened for it is handed over with [adopt] and released by [finish]. A port
 * offered for a stale, finished or expired token, or a second port for the same token, is closed
 * immediately.
 *
 * Arbitration: a companion operation starts only while nothing holds the device; a flash starts
 * when nothing or the companion holds it and preempts the companion (its port is closed). A
 * companion open still waiting for its port past its handoff deadline is expired lazily by the
 * next [begin]/[beginCompanion]. A flash ends only through [finish], [expireHandoff] (its deadline
 * timer), an EXPIRED [adopt] or [close], so the flash owner always observes its own end.
 *
 * [onEnded] runs once per operation, outside the lock, on whichever path ended it (finish,
 * expiry, preemption, owner shutdown). Pure Kotlin: the Activity keeps the Android USB calls,
 * the ViewModel holds the owner.
 */
class FlashPortOwner(
    private val clock: () -> Long = ::monotonicMs,
    private val onEnded: (token: Long, kind: Kind) -> Unit = { _, _ -> },
) {
    enum class Kind { COMPANION, FLASH }
    enum class Adopt { ADOPTED, STALE, DUPLICATE, EXPIRED, CLOSED }

    private class Op(val token: Long, val kind: Kind, val handoffDeadlineMs: Long) { var lease: PortLease? = null }

    private var nextToken = 0L
    private var op: Op? = null
    private var closed = false

    /** Token of the operation holding the device, of either kind. */
    val activeToken: Long? @Synchronized get() = op?.token

    /** Who holds the device, or null. */
    val holder: Kind? @Synchronized get() = op?.kind

    /** Token of the active flash operation, or null when no flash holds the device. */
    val flashToken: Long? @Synchronized get() = op?.takeIf { it.kind == Kind.FLASH }?.token

    /**
     * Start a flash whose port must be adopted within [handoffTimeoutMs]. Preempts (and closes)
     * a companion operation. Null while another flash holds the device or after [close].
     */
    fun begin(handoffTimeoutMs: Long): Long? {
        val ended = ArrayList<Op>(1)
        val token = synchronized(this) {
            if (closed) return@synchronized null
            val cur = op
            if (cur != null && cur.kind == Kind.FLASH) return@synchronized null
            if (cur != null) ended += cur
            Op(++nextToken, Kind.FLASH, clock() + handoffTimeoutMs).also { op = it }.token
        }
        ended.forEach(::release)
        return token
    }

    /**
     * Start a companion link open whose port must be adopted within [handoffTimeoutMs]. Null while
     * any operation (a flash, or another companion open/link) holds the device, or after [close].
     */
    fun beginCompanion(handoffTimeoutMs: Long): Long? {
        val ended = ArrayList<Op>(1)
        val token = synchronized(this) {
            if (closed) return@synchronized null
            val cur = op
            if (cur != null && (cur.kind == Kind.FLASH || !handoffExpired(cur))) return@synchronized null
            if (cur != null) ended += cur
            Op(++nextToken, Kind.COMPANION, clock() + handoffTimeoutMs).also { op = it }.token
        }
        ended.forEach(::release)
        return token
    }

    /** Hand [lease] to operation [token]. Anything but [Adopt.ADOPTED] has already closed [lease]; EXPIRED also ended the operation. */
    fun adopt(token: Long, lease: PortLease): Adopt {
        var expired: Op? = null
        val result = synchronized(this) {
            val cur = op
            when {
                closed -> Adopt.CLOSED
                cur == null || cur.token != token -> Adopt.STALE
                cur.lease != null -> Adopt.DUPLICATE
                clock() > cur.handoffDeadlineMs -> { op = null; expired = cur; Adopt.EXPIRED }
                else -> { cur.lease = lease; Adopt.ADOPTED }
            }
        }
        if (result != Adopt.ADOPTED) lease.close()
        expired?.let(::release)
        return result
    }

    /** End operation [token] and release its port once. False when [token] is not the active operation. */
    fun finish(token: Long): Boolean {
        val ended = synchronized(this) {
            val cur = op
            if (cur == null || cur.token != token) return false
            op = null
            cur
        }
        release(ended)
        return true
    }

    /** Deadline check: ends [token] if it is still waiting for its port past the handoff deadline. */
    fun expireHandoff(token: Long): Boolean {
        val ended = synchronized(this) {
            val cur = op ?: return false
            if (cur.token != token || !handoffExpired(cur)) return false
            op = null
            cur
        }
        release(ended)
        return true
    }

    /** Owner shutdown (ViewModel cleared): release any held port and reject every later begin/adopt. */
    fun close() {
        val ended = synchronized(this) {
            closed = true
            val cur = op
            op = null
            cur
        }
        ended?.let(::release)
    }

    private fun handoffExpired(cur: Op): Boolean = cur.lease == null && clock() >= cur.handoffDeadlineMs

    private fun release(ended: Op) {
        ended.lease?.close()
        try { onEnded(ended.token, ended.kind) } catch (_: Exception) { /* listener logs its own failures */ }
    }
}

/**
 * Launch [block] as the user of [token]'s port. The operation is finished both from the body's
 * finally and from job completion: a job cancelled before its body starts never runs that body,
 * so a finally alone would leak the port. [onAbandoned] runs only when the completion handler,
 * not the body, ended the operation (cancellation before start).
 */
fun CoroutineScope.launchFlashOwned(
    owner: FlashPortOwner,
    token: Long,
    context: CoroutineContext,
    onAbandoned: () -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = launch(context) {
        try { block() } finally { owner.finish(token) }
    }
    job.invokeOnCompletion { if (owner.finish(token)) onAbandoned() }
    return job
}

/**
 * Pending USB permission requests, correlated by request id AND device name, each with a deadline.
 * A result for an unknown or already-settled id is STALE; a result naming another device is
 * WRONG_DEVICE and leaves the request pending; a result after the deadline settles it as TIMED_OUT.
 * Ids are process-wide so a request from a destroyed Activity never matches a new one.
 */
class UsbPermissionRequests<T>(private val clock: () -> Long = ::monotonicMs) {
    enum class Outcome { GRANTED, DENIED, TIMED_OUT, CANCELLED, WRONG_DEVICE, STALE }

    /** [payload] is non-null exactly when this call settled the request. */
    class Result<T>(val outcome: Outcome, val payload: T?)

    private class Pending<T>(val deviceName: String, val deadlineMs: Long, val payload: T)

    private val pending = LinkedHashMap<Int, Pending<T>>()

    val size: Int @Synchronized get() = pending.size

    @Synchronized
    fun begin(deviceName: String, timeoutMs: Long, payload: T): Int {
        val id = ids.incrementAndGet()
        pending[id] = Pending(deviceName, clock() + timeoutMs, payload)
        return id
    }

    @Synchronized
    fun resolve(id: Int, deviceName: String?, granted: Boolean): Result<T> {
        val p = pending[id] ?: return Result(Outcome.STALE, null)
        if (deviceName == null || deviceName != p.deviceName) return Result(Outcome.WRONG_DEVICE, null)
        pending.remove(id)
        val outcome = when {
            clock() > p.deadlineMs -> Outcome.TIMED_OUT
            granted -> Outcome.GRANTED
            else -> Outcome.DENIED
        }
        return Result(outcome, p.payload)
    }

    /** Deadline timer: settles [id] as timed out and returns its payload, or null if settled or not yet due. */
    @Synchronized
    fun expire(id: Int): T? {
        val p = pending[id] ?: return null
        if (clock() < p.deadlineMs) return null
        pending.remove(id)
        return p.payload
    }

    /** Settle every pending request as cancelled (receiver owner going away). */
    @Synchronized
    fun drain(): List<T> {
        val all = pending.values.map { it.payload }
        pending.clear()
        return all
    }

    private companion object {
        val ids = AtomicInteger(0)
    }
}
