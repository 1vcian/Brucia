package app.brucia

import app.brucia.remote.RemoteRun
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * F34: the Remote mirror watchdog belongs to one attempt. A rejected or lost companion_exit never
 * leaves one, retries cannot orphan an earlier one, and Stop/disconnect end every watchdog.
 */
class RemoteRunTest {
    private val live = AtomicInteger()
    private val started = AtomicInteger()
    private val watchdog: suspend () -> Unit = {
        live.incrementAndGet(); started.incrementAndGet()
        try { awaitCancellation() } finally { live.decrementAndGet() }
    }

    @Test(timeout = 5000) fun failedExitRetriesThenStopLeaveNoWatchdog() = runVirtual {
        val run = RemoteRun(this)
        repeat(3) {
            run.start(enter = { delay(50); false }, watchdog = watchdog, mirror = { awaitCancellation() })
            delay(100)
            assertEquals("failed attempt $it left a watchdog", 0, live.get())
        }
        run.stop()
        settle()
        assertEquals(0, started.get())
    }

    @Test(timeout = 5000) fun successfulRunHasOneWatchdogUntilStop() = runVirtual {
        val run = RemoteRun(this)
        run.start(enter = { false }, watchdog = watchdog, mirror = { awaitCancellation() }) // failed first try
        settle()
        run.start(enter = { true }, watchdog = watchdog, mirror = { awaitCancellation() })
        until { live.get() == 1 }
        run.stop()
        settle()
        assertEquals(0, live.get())
    }

    @Test(timeout = 5000) fun restartWhileEnteringOrMirroringNeverStacksWatchdogs() = runVirtual {
        val run = RemoteRun(this)
        val exitGate = CompletableDeferred<Boolean>()
        run.start(enter = { exitGate.await() }, watchdog = watchdog, mirror = { awaitCancellation() })
        settle()
        // A new attempt replaces the pending one: its late ACK must not arm a watchdog.
        run.start(enter = { true }, watchdog = watchdog, mirror = { awaitCancellation() })
        exitGate.complete(true)
        until { live.get() == 1 }
        run.start(enter = { true }, watchdog = watchdog, mirror = { awaitCancellation() })
        settle()
        assertEquals(1, live.get())
        run.stop()
        settle()
        assertEquals(0, live.get())
    }

    @Test(timeout = 5000) fun disconnectDuringEntryCancelsBeforeAnyWatchdog() = runVirtual {
        val run = RemoteRun(this)
        val exitGate = CompletableDeferred<Boolean>()
        run.start(enter = { withContext(NonCancellable) { exitGate.await() } }, watchdog = watchdog, mirror = { awaitCancellation() })
        settle()
        run.stop()               // disconnect while companion_exit is outstanding
        exitGate.complete(true)  // late ACK
        settle()
        assertEquals(0, started.get())
    }

    @Test(timeout = 5000) fun mirrorEndingEndsItsWatchdog() = runVirtual {
        val run = RemoteRun(this)
        val linkGone = CompletableDeferred<Unit>()
        run.start(enter = { true }, watchdog = watchdog, mirror = { linkGone.await() })
        until { live.get() == 1 }
        linkGone.complete(Unit)
        settle()
        assertEquals(0, live.get())
        run.stop()
    }
}
