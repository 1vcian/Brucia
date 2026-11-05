package app.brucia

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import java.util.PriorityQueue
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Single-threaded virtual clock for JVM tests: a dispatcher and [Delay] whose time only moves
 * when the test advances it. delay/withTimeout on this dispatcher never read the wall clock, so
 * host load cannot make a timeout fire early or late. Not thread-safe by design: work resumed
 * from another thread is recorded in [foreignThread] and fails [runVirtual].
 */
@OptIn(InternalCoroutinesApi::class)
internal class VirtualClock : CoroutineDispatcher(), Delay {
    private class Timer(val at: Long, val seq: Long, val action: () -> Unit)
    var now = 0L
        private set
    private var seq = 0L
    private val owner = Thread.currentThread()
    @Volatile var foreignThread: String? = null
        private set
    private val ready = ArrayDeque<Runnable>()
    private val timers = PriorityQueue(compareBy<Timer>({ it.at }, { it.seq }))
    val timerCount: Int get() = timers.size

    private fun checkThread() {
        val current = Thread.currentThread()
        if (current !== owner && foreignThread == null) foreignThread = current.name
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) { checkThread(); ready.addLast(block) }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val timer = schedule(timeMillis) { continuation.resume(Unit) }
        continuation.invokeOnCancellation { timers.remove(timer) }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val timer = schedule(timeMillis) { block.run() }
        return object : DisposableHandle { override fun dispose() { timers.remove(timer) } }
    }

    private fun schedule(ms: Long, action: () -> Unit): Timer {
        checkThread()
        return Timer(now + ms.coerceAtLeast(0), seq++, action).also { timers.add(it) }
    }

    /**
     * Same clock, but resumes in place like Dispatchers.Unconfined (for code under test that takes
     * an injected dispatcher). Unlike Unconfined, its delays and timeouts are virtual.
     */
    val unconfined: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {
        override fun isDispatchNeeded(context: CoroutineContext) = false
        override fun dispatch(context: CoroutineContext, block: Runnable) = this@VirtualClock.dispatch(context, block)
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
            this@VirtualClock.scheduleResumeAfterDelay(timeMillis, continuation)
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext) =
            this@VirtualClock.invokeOnTimeout(timeMillis, block, context)
    }

    fun runCurrent() { while (ready.isNotEmpty()) ready.removeFirst().run() }

    fun advanceBy(ms: Long) {
        val target = now + ms
        runCurrent()
        while (true) {
            val next = timers.peek() ?: break
            if (next.at > target) break
            fire(timers.poll() ?: break)
        }
        now = target
    }

    /** Jump to the earliest pending timer and run everything due then; false when none is pending. */
    fun advanceToNextTimer(): Boolean {
        runCurrent()
        val next = timers.poll() ?: return false
        fire(next)
        return true
    }

    private fun fire(timer: Timer) {
        now = timer.at
        timer.action()
        runCurrent()
    }
}

/**
 * Virtual-time stand-in for Dispatchers.Unconfined inside [runVirtual]: inject it where the code
 * under test takes a dispatcher, so its delays and timeouts stay on the test's virtual clock.
 */
internal val CoroutineScope.virtualUnconfined: CoroutineDispatcher
    get() = (coroutineContext[ContinuationInterceptor] as? VirtualClock
        ?: error("virtualUnconfined is only available inside runVirtual")).unconfined

/** [CoroutineScope] of a [runVirtual] test body, with access to its [clock]. */
internal class VirtualTestScope(val clock: VirtualClock, scope: CoroutineScope) : CoroutineScope by scope

/**
 * Drop-in for runBlocking in tests whose code runs on the calling thread: same structured
 * waiting for children, but every delay/withTimeout is virtual. When the body is suspended
 * with nothing runnable, time jumps to the next timer (as kotlinx-coroutines-test runTest
 * does). Fails instead of hanging when nothing can ever resume the body, and when code
 * under test resumed it from another thread (that code needs real time; use runBlocking).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> runVirtual(maxVirtualMs: Long = 24 * 3_600_000L, block: suspend VirtualTestScope.() -> T): T {
    val clock = VirtualClock()
    val job = Job()
    val body = CoroutineScope(clock + job).async { VirtualTestScope(clock, this).block() }
    try {
        while (true) {
            clock.runCurrent()
            clock.foreignThread?.let {
                throw AssertionError("runVirtual: coroutine work arrived from thread '$it'; this test needs real threads")
            }
            if (body.isCompleted) return body.getCompleted()
            if (!clock.advanceToNextTimer())
                throw AssertionError("runVirtual: test body suspended with no runnable work and no pending timer (would hang)")
            if (clock.now > maxVirtualMs) throw AssertionError("runVirtual: virtual time exceeded $maxVirtualMs ms")
        }
    } finally {
        job.cancel()
        clock.runCurrent()
    }
}
