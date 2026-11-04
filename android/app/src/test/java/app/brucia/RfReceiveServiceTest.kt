package app.brucia

import app.brucia.subghz.RfReceiveService
import app.brucia.subghz.RfReceiveService.Captured
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Reuses GateRunner / settle from DeviceOperationTest; single-threaded, no wall-clock waits. */
class RfReceiveServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    private val captured = CopyOnWriteArrayList<Captured>()
    private var connected = true

    private fun CoroutineScope.svc() = RfReceiveService(this, runner, { connected }) { captured += it }

    private suspend fun answer(i: Int, ok: Boolean, out: String) {
        settle()
        assertTrue("call $i not sent", runner.calls.size > i)
        runner.reply(i, ok, out)
        settle()
    }

    @Test(timeout = 5_000) fun decodedCaptureIsSavedWithReplayCommand() = runBlocking {
        val s = svc()
        s.capture(433_920_000L)
        assertTrue(s.busy.value)
        answer(0, true, DECODED)
        assertEquals(DECODED, s.last.value)
        assertFalse(s.busy.value)
        assertEquals(listOf(Captured("00 00 00 00 00 44 55 33", "Princeton", "rf tx 0000000000445533 433920000 174 10")), captured.toList())
        assertEquals(listOf("rf rx 433920000" to 20_000L), runner.calls.toList())
    }

    @Test(timeout = 5_000) fun captureWithoutReplayFieldsKeepsRawOutput() = runBlocking {
        val s = svc()
        s.capture(315_000_000L)
        answer(0, true, "Protocol: RAW\nRAW_Data: 100 -200 300")
        assertEquals(listOf(Captured("signal", "RAW", "Protocol: RAW\nRAW_Data: 100 -200 300")), captured.toList())
    }

    @Test(timeout = 5_000) fun failureShowsOutputButSavesNothing() = runBlocking {
        val s = svc()
        s.capture(433_920_000L)
        answer(0, false, "No response from the device.")
        assertEquals("No response from the device.", s.last.value)
        assertTrue(captured.isEmpty())
    }

    @Test(timeout = 5_000) fun supersededOrStoppedCaptureIsIgnored() = runBlocking {
        val s = svc()
        s.capture(433_920_000L)
        settle()
        s.capture(868_350_000L)
        settle()
        runner.reply(0, true, DECODED)
        settle()
        assertNull(s.last.value)
        s.stop()
        assertEquals("Stopped." to false, s.status.value to s.busy.value)
        runner.reply(1, true, DECODED)
        settle()
        assertNull(s.last.value)
        assertTrue(captured.isEmpty())
    }

    @Test(timeout = 5_000) fun notConnectedSendsNothing() = runBlocking {
        connected = false
        val s = svc()
        s.capture(433_920_000L)
        settle()
        assertTrue(runner.calls.isEmpty())
        assertEquals("Connect the device over USB first." to false, s.status.value to s.busy.value)
    }

    private companion object {
        val DECODED = """
            Frequency: 433920000
            Preset: 0
            Protocol: Princeton
            Bit: 24
            Key: 00 00 00 00 00 44 55 33
            TE: 174
        """.trimIndent()
    }
}
