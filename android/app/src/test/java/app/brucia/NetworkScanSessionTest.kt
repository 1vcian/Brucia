package app.brucia

import app.brucia.netscan.NetworkScanSession
import app.brucia.netscan.NetworkScanState
import app.brucia.netscan.ScanHost
import app.brucia.netscan.ServicePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Network Scan state holder that replaced plain `remember` state in the screen: it outlives
 * the composition (Activity-scoped ViewModel), so these tests pin what a rotation relies on.
 */
class NetworkScanSessionTest {

    /** Fake subnet scan on the virtual clock: one host probed every [stepMs], [up] report a port. */
    private class FakeScanner(val total: Int = 4, val stepMs: Long = 100, val up: Set<Int> = setOf(3, 1)) {
        var calls = 0
        var lateProgress: ((Int, Int) -> Unit)? = null
        var lateHost: ((ScanHost) -> Unit)? = null
        val fn: suspend (String, (Int, Int) -> Unit, (ScanHost) -> Unit) -> Unit = { base, onProgress, onHost ->
            calls++
            lateProgress = onProgress; lateHost = onHost
            // Report hosts out of order (as the parallel probes do) to check the sorting.
            for ((i, h) in (1..total).sortedByDescending { it }.withIndex()) {
                delay(stepMs)
                onProgress(i + 1, total)
                if (h in up) onHost(ScanHost("$base$h", listOf(ServicePort(80, "http"))))
            }
        }
    }

    private fun scoped(body: suspend VirtualTestScope.(CoroutineScope) -> Unit) = runVirtual {
        val s = CoroutineScope(coroutineContext + Job())
        try { body(s) } finally { s.cancel() }
    }

    @Test fun scanReportsProgressAndSortedHostsThenFinishes() = scoped { s ->
        val fake = FakeScanner()
        val session = NetworkScanSession(s, fake.fn)
        assertEquals(NetworkScanState(), session.state.value)

        assertTrue(session.start("10.0.0."))
        assertTrue(session.state.value.scanning)
        assertTrue(session.isRunning)

        clock.advanceBy(250)
        assertEquals(2, session.state.value.progress)
        assertEquals(listOf("10.0.0.3"), session.state.value.hosts.map { it.ip })

        clock.advanceBy(1_000)
        val done = session.state.value
        assertFalse(done.scanning)
        assertFalse(session.isRunning)
        assertEquals(4, done.progress)
        assertEquals(4, done.total)
        assertEquals(listOf("10.0.0.1", "10.0.0.3"), done.hosts.map { it.ip })
        assertEquals(1, fake.calls)
    }

    @Test fun startWhileRunningIsIgnoredSoARecreatedScreenCannotDuplicateTheScan() = scoped { s ->
        val fake = FakeScanner()
        val session = NetworkScanSession(s, fake.fn)
        assertTrue(session.start("10.0.0."))
        clock.advanceBy(150)
        // The recreated screen observes the same session: state is still there, a second start is refused.
        val mid = session.state.value
        assertFalse(session.start("10.0.0."))
        assertEquals(mid, session.state.value)
        clock.advanceBy(1_000)
        assertEquals(1, fake.calls)
        assertEquals(4, session.state.value.progress)
    }

    @Test fun finishedResultsStayUntilReset() = scoped { s ->
        val session = NetworkScanSession(s, FakeScanner().fn)
        session.start("10.0.0.")
        clock.advanceBy(1_000)
        val done = session.state.value
        clock.advanceBy(60_000)
        assertEquals(done, session.state.value)
        session.reset()
        assertEquals(NetworkScanState(), session.state.value)
    }

    @Test fun stopKeepsHostsAndLateCallbacksOfTheStoppedRunAreIgnored() = scoped { s ->
        val fake = FakeScanner(total = 10, up = setOf(10, 9, 2))
        val session = NetworkScanSession(s, fake.fn)
        session.start("10.0.0.")
        clock.advanceBy(250)
        session.stop()
        val stopped = session.state.value
        assertFalse(stopped.scanning)
        assertEquals(listOf("10.0.0.9", "10.0.0.10"), stopped.hosts.map { it.ip })

        // A probe of the stopped run finishing late (IO threads are not cancelled mid-connect).
        val oldProgress = fake.lateProgress!!
        val oldHost = fake.lateHost!!
        oldProgress(9, 10)
        oldHost(ScanHost("10.0.0.7", emptyList()))
        assertEquals(stopped, session.state.value)

        // A new scan starts from scratch and the old run still cannot touch it.
        assertTrue(session.start("10.0.0."))
        oldHost(ScanHost("10.0.0.8", emptyList()))
        assertEquals(emptyList<ScanHost>(), session.state.value.hosts)
        clock.advanceBy(5_000)
        assertEquals(listOf("10.0.0.2", "10.0.0.9", "10.0.0.10"), session.state.value.hosts.map { it.ip })
        assertEquals(2, fake.calls)
    }

    @Test fun resetDuringAScanCancelsIt() = scoped { s ->
        val fake = FakeScanner()
        val session = NetworkScanSession(s, fake.fn)
        session.start("10.0.0.")
        clock.advanceBy(150)
        session.reset()
        clock.advanceBy(5_000)
        assertEquals(NetworkScanState(), session.state.value)
        assertFalse(session.isRunning)
    }

    @Test fun failingScanKeepsResultsAndClearsTheScanningFlag() = scoped { s ->
        val session = NetworkScanSession(s) { base, onProgress, onHost ->
            delay(50); onProgress(1, 254); onHost(ScanHost("${base}5", emptyList()))
            error("network down")
        }
        session.start("10.0.0.")
        clock.advanceBy(100)
        assertFalse(session.state.value.scanning)
        assertFalse(session.isRunning)
        assertEquals(1, session.state.value.progress)
        assertEquals(listOf("10.0.0.5"), session.state.value.hosts.map { it.ip })
        assertTrue(session.start("10.0.0."))
    }

    @Test fun ipKeySortsByLastOctet() {
        assertEquals(listOf("10.0.0.2", "10.0.0.10", "10.0.0.100"),
            listOf("10.0.0.100", "10.0.0.2", "10.0.0.10").sortedBy { NetworkScanSession.ipKey(it) })
        assertEquals(0, NetworkScanSession.ipKey("bogus"))
    }
}
