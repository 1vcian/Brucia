package app.brucia

import app.brucia.ir.IrButton
import app.brucia.ir.IrReceiveService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class IrReceiveServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    private val captured = CopyOnWriteArrayList<IrButton>()
    private var connected = true

    private fun CoroutineScope.svc() = IrReceiveService(this, runner, { connected }) { captured += it }

    private suspend fun answer(i: Int, ok: Boolean, out: String) {
        until { runner.calls.size > i }
        runner.reply(i, ok, out)
    }

    @Test fun receiveParsesAndHoldsResult() = runBlocking {
        val s = svc()
        s.receiveOnce()
        assertEquals(RX_RUNNING to true, s.rxStatus.value to s.rxBusy.value)
        answer(0, true, NEC_BLOCK)
        until { !s.rxBusy.value }
        assertEquals("Received: Power (NEC)", s.rxStatus.value)
        assertEquals(IrButton("Power", "NEC", "04000000", "08000000"), s.rxResult.value)
        s.receiveOnce()
        assertNull(s.rxResult.value)
        answer(1, true, RAW_BLOCK)
        until { !s.rxBusy.value }
        assertEquals("Received: Learned (raw)", s.rxStatus.value)
        assertTrue(s.rxResult.value!!.raw)
        assertEquals(listOf("ir_rx" to 20_000L, "ir_rx" to 20_000L), runner.calls.toList())
    }

    @Test fun firmwareOneLineSummaryIsDecodedForReceiveAndCapture() = runBlocking {
        val s = svc()
        s.receiveOnce()
        answer(0, true, "NEC addr=04 00 00 00 cmd=08 00 00 00")
        until { !s.rxBusy.value }
        assertEquals("Received: NEC 08 00 00 00 (NEC)", s.rxStatus.value)
        assertEquals(IrButton("NEC 08 00 00 00", "NEC", "04000000", "08000000"), s.rxResult.value)
        s.captureKey("Vol +")
        answer(1, true, "raw: 9000 4500 560 560")
        until { !s.captureBusy.value }
        assertEquals("Saved: Vol +", s.captureStatus.value)
        assertEquals(listOf(IrButton("Vol +", "", "", "", raw = true, data = "9000 4500 560 560")), captured.toList())
    }

    @Test fun receiveFallbackTexts() = runBlocking {
        val s = svc()
        val cases = listOf(
            Triple(false, "no IR signal", "no IR signal"),
            Triple(false, "", "Receive failed."),
            Triple(true, "NEC addr=0x04 cmd=0x08", "NEC addr=0x04 cmd=0x08"),
            Triple(true, "", "No signal decoded."),
        )
        cases.forEachIndexed { i, (ok, out, text) ->
            s.receiveOnce()
            answer(i, ok, out)
            until { !s.rxBusy.value }
            assertEquals(text, s.rxStatus.value)
            assertNull(s.rxResult.value)
        }
    }

    @Test fun supersededOrStoppedReceiveNeverSetsResult() = runBlocking {
        val s = svc()
        s.receiveOnce()
        until { runner.calls.size == 1 }
        s.receiveOnce()
        until { runner.calls.size == 2 }
        runner.reply(0, true, NEC_BLOCK)
        delay(20)
        assertNull(s.rxResult.value)
        assertEquals(RX_RUNNING, s.rxStatus.value)
        s.stopReceive()
        runner.reply(1, true, NEC_BLOCK)
        delay(20)
        assertNull(s.rxResult.value)
        assertEquals("Stopped." to false, s.rxStatus.value to s.rxBusy.value)
    }

    @Test fun markSavedClearsResultEvenOffline() = runBlocking {
        val s = svc()
        s.receiveOnce()
        answer(0, true, NEC_BLOCK)
        until { s.rxResult.value != null }
        connected = false
        s.markSaved(s.rxResult.value!!)
        assertNull(s.rxResult.value)
        assertEquals("Saved: Power" to false, s.rxStatus.value to s.rxBusy.value)
        delay(20)
        assertEquals(1, runner.calls.size)
    }

    @Test fun captureNamesKeyAndCallsBackOnce() = runBlocking {
        val s = svc()
        s.captureKey("  ")
        assertEquals(CAPTURE_RUNNING to true, s.captureStatus.value to s.captureBusy.value)
        answer(0, true, NEC_BLOCK)
        until { !s.captureBusy.value }
        assertEquals("Saved: Power", s.captureStatus.value)
        s.captureKey(" Vol up ")
        answer(1, true, NEC_BLOCK)
        until { !s.captureBusy.value }
        assertEquals("Saved: Vol up", s.captureStatus.value)
        assertEquals(listOf("Power", "Vol up"), captured.map { it.name })
        assertEquals(listOf("ir_rx" to 20_000L, "ir_rx" to 20_000L), runner.calls.toList())
    }

    @Test fun captureFailureTextsNeverCallBack() = runBlocking {
        val s = svc()
        val cases = listOf(
            Triple(false, "no IR signal", "no IR signal"),
            Triple(false, "", "Capture failed."),
            Triple(true, "NEC addr=0x04 cmd=0x08", "No signal decoded - try again."),
        )
        cases.forEachIndexed { i, (ok, out, text) ->
            s.captureKey("k")
            answer(i, ok, out)
            until { !s.captureBusy.value }
            assertEquals(text, s.captureStatus.value)
        }
        assertTrue(captured.isEmpty())
    }

    @Test fun supersededOrStoppedCaptureNeverCallsBack() = runBlocking {
        val s = svc()
        s.captureKey("a")
        until { runner.calls.size == 1 }
        s.captureKey("b")
        until { runner.calls.size == 2 }
        runner.reply(0, true, NEC_BLOCK)
        delay(20)
        assertTrue(captured.isEmpty())
        s.stopCapture()
        runner.reply(1, true, NEC_BLOCK)
        delay(20)
        assertTrue(captured.isEmpty())
        assertEquals("Stopped." to false, s.captureStatus.value to s.captureBusy.value)
    }

    @Test fun receiveAndCaptureDoNotSupersedeEachOther() = runBlocking {
        val s = svc()
        s.receiveOnce()
        s.captureKey("k")
        until { runner.calls.size == 2 }
        assertTrue(s.rxBusy.value && s.captureBusy.value)
        runner.reply(1, true, RAW_BLOCK)
        runner.reply(0, true, NEC_BLOCK)
        until { !s.rxBusy.value && !s.captureBusy.value }
        assertEquals("Received: Power (NEC)", s.rxStatus.value)
        assertEquals("Saved: k", s.captureStatus.value)
        assertEquals(listOf("k"), captured.map { it.name })
        assertTrue(captured.single().raw)
    }

    @Test fun notConnectedSendsNothing() = runBlocking {
        connected = false
        val s = svc()
        s.receiveOnce()
        s.captureKey("k")
        val nc = "Connect the device over USB first."
        assertEquals(nc to nc, s.rxStatus.value to s.captureStatus.value)
        assertFalse(s.rxBusy.value || s.captureBusy.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    private companion object {
        const val RX_RUNNING = "Receiving - point a remote at the device and press a button (10s)..."
        const val CAPTURE_RUNNING = "Point a remote at the device and press the button (10s)..."
        val NEC_BLOCK = """
            Filetype: IR signals file
            Version: 1
            #
            name: Power
            type: parsed
            protocol: NEC
            address: 04 00 00 00
            command: 08 00 00 00
        """.trimIndent()
        val RAW_BLOCK = """
            name: Learned
            type: raw
            frequency: 38000
            duty_cycle: 0.330000
            data: 9000 4500 560 560 560 1690
        """.trimIndent()
    }
}
