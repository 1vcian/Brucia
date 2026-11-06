package app.brucia

import app.brucia.wifi.PcapDissectSession
import app.brucia.wifi.PcapDissectState
import app.brucia.wifi.PcapWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** PCAP dissect state holder (Activity-scoped ViewModel): results and a parse survive a rotation. */
class PcapDissectSessionTest {

    private fun beacon(bssid: Int): ByteArray = ByteArray(24).also { f ->
        f[0] = 0x80.toByte()
        for (i in 4 until 10) f[i] = 0xFF.toByte()
        f[15] = bssid.toByte(); f[21] = bssid.toByte()
    }

    private fun pcap(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        PcapWriter.write(out, List(n) { i -> i.toLong() * 1000 to beacon(i % 3) })
        return out.toByteArray()
    }

    private fun scoped(body: suspend VirtualTestScope.(PcapDissectSession) -> Unit) = runVirtual {
        val s = CoroutineScope(coroutineContext + Job())
        try { body(PcapDissectSession(s, virtualUnconfined, virtualUnconfined)) } finally { s.cancel() }
    }

    @Test fun openParsesAndKeepsTheResultUntilReset() = scoped { session ->
        assertTrue(session.open("cap.pcap") { pcap(5) })
        assertTrue(session.state.value.loading)
        assertEquals("cap.pcap", session.state.value.fileName)
        clock.runCurrent()
        val done = session.state.value
        assertFalse(done.loading)
        assertEquals(5, done.summary!!.frames)
        assertEquals(5, done.rows.size)
        assertEquals("", done.error)

        clock.advanceBy(60_000)
        assertEquals(done, session.state.value)
        session.reset()
        assertEquals(PcapDissectState(), session.state.value)
    }

    @Test fun notAPcapAndReadFailuresAreReported() = scoped { session ->
        session.open("x.bin") { ByteArray(16) }
        clock.runCurrent()
        assertNull(session.state.value.summary)
        assertEquals("Not a classic pcap, or no frames found.", session.state.value.error)
        assertFalse(session.state.value.loading)

        session.open("gone.pcap") { throw IOException("No such file") }
        clock.runCurrent()
        assertEquals("Failed to read: No such file", session.state.value.error)
        assertEquals("gone.pcap", session.state.value.fileName)
        assertFalse(session.state.value.loading)
    }

    @Test fun secondOpenWhileParsingIsRefused() = runVirtual {
        val s = CoroutineScope(coroutineContext + Job())
        try {
            // Queued dispatcher: the first parse is still pending when the second open arrives.
            val session = PcapDissectSession(s, clock, clock)
            var reads = 0
            assertTrue(session.open("a.pcap") { reads++; pcap(2) })
            assertFalse(session.open("b.pcap") { reads++; pcap(3) })
            clock.runCurrent()
            assertEquals(1, reads)
            assertEquals("a.pcap", session.state.value.fileName)
            assertNotNull(session.state.value.summary)
            assertTrue(session.open("b.pcap") { reads++; pcap(3) })
            clock.runCurrent()
            assertEquals(3, session.state.value.summary!!.frames)
        } finally { s.cancel() }
    }

    @Test fun resetDuringAParseDropsItsResult() = runVirtual {
        val s = CoroutineScope(coroutineContext + Job())
        try {
            val session = PcapDissectSession(s, clock, clock)
            session.open("a.pcap") { pcap(2) }
            session.reset()
            clock.runCurrent()
            assertEquals(PcapDissectState(), session.state.value)
        } finally { s.cancel() }
    }

    @Test fun resetWhileTheBlockingReadRunsLeavesTheStateCleared() = scoped { session ->
        // The read (on the IO dispatcher) cannot be interrupted; the user leaves meanwhile.
        session.open("a.pcap") { session.reset(); pcap(2) }
        clock.runCurrent()
        assertEquals(PcapDissectState(), session.state.value)
    }

    @Test fun anAbandonedParseNeverOverwritesANewerOpen() = scoped { session ->
        session.open("a.pcap") {
            session.reset()
            assertTrue(session.open("b.pcap") { pcap(3) })
            pcap(2)
        }
        clock.runCurrent()
        val s = session.state.value
        assertEquals("b.pcap", s.fileName)
        assertFalse(s.loading)
        assertEquals(3, s.summary!!.frames)
    }
}
