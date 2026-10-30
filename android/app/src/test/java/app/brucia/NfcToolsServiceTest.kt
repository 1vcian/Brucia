package app.brucia

import app.brucia.nfc.NfcToolsService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Reuses GateRunner / until from DeviceOperationTest. */
class NfcToolsServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    private var connected = true

    private fun CoroutineScope.svc() = NfcToolsService(this, runner) { connected }

    /** Wait for call [i], complete it, and wait for the line to go idle via [busy]. */
    private suspend fun finish(i: Int, ok: Boolean, out: String, busy: () -> Boolean) {
        until { runner.calls.size == i + 1 }
        runner.reply(i, ok, out)
        until { !busy() }
    }

    @Test(timeout = 5_000) fun pushNdefTextsAndCommand() = runBlocking {
        val s = svc()
        s.pushNdef("url", "  https://x  ")
        assertEquals("Pushing NDEF (url) - tap a device..." to true, s.ndefStatus.value to s.ndefBusy.value)
        finish(0, true, "") { s.ndefBusy.value }
        assertEquals("NDEF pushed.", s.ndefStatus.value)
        s.pushNdef("text", "hi")
        finish(1, false, "") { s.ndefBusy.value }
        assertEquals("NDEF push failed.", s.ndefStatus.value)
        s.pushNdef("url", "x")
        finish(2, true, "device acked") { s.ndefBusy.value }
        assertEquals("device acked", s.ndefStatus.value)
        // Payload is trimmed into the command and the timeout cites the ~60s emulate bound.
        assertEquals(
            listOf("nfc_ndef_push url https://x", "nfc_ndef_push text hi", "nfc_ndef_push url x")
                .map { it to NfcToolsService.NDEF_TIMEOUT_MS },
            runner.calls.toList(),
        )
    }

    @Test(timeout = 5_000) fun pushNdefBlankPayloadRejectedLocally() = runBlocking {
        val s = svc()
        s.pushNdef("url", "   ")
        assertEquals("Enter a payload first." to false, s.ndefStatus.value to s.ndefBusy.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun readEmvTextsAndCommand() = runBlocking {
        val s = svc()
        s.readEmv()
        assertEquals("Reading card - hold it on the reader..." to true, s.emvStatus.value to s.emvBusy.value)
        finish(0, true, "") { s.emvBusy.value }
        assertEquals("No card data.", s.emvStatus.value)
        s.readEmv()
        finish(1, false, "") { s.emvBusy.value }
        assertEquals("EMV read failed.", s.emvStatus.value)
        s.readEmv()
        finish(2, true, "MASTERCARD ****1234") { s.emvBusy.value }
        assertEquals("MASTERCARD ****1234", s.emvStatus.value)
        assertEquals(List(3) { "nfc_emv_read" to NfcToolsService.UNBOUNDED_TIMEOUT_MS }, runner.calls.toList())
    }

    @Test(timeout = 5_000) fun writeUidNormalizesTextsAndCommand() = runBlocking {
        val s = svc()
        s.writeUid(" de ad be ef ")
        assertEquals("Writing UID deadbeef..." to true, s.uidStatus.value to s.uidBusy.value)
        finish(0, true, "") { s.uidBusy.value }
        assertEquals("UID written.", s.uidStatus.value)
        s.writeUid("deadbeef")
        finish(1, false, "") { s.uidBusy.value }
        assertEquals("Write failed.", s.uidStatus.value)
        assertEquals(
            List(2) { "nfc_write_uid deadbeef" to NfcToolsService.UNBOUNDED_TIMEOUT_MS },
            runner.calls.toList(),
        )
    }

    @Test(timeout = 5_000) fun writeUidBlankRejectedLocally() = runBlocking {
        val s = svc()
        s.writeUid("   ")
        assertEquals("Enter a hex UID first." to false, s.uidStatus.value to s.uidBusy.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun advancedTilesLabelsCommandsAndTimeouts() = runBlocking {
        val s = svc()
        data class Case(val go: () -> Unit, val cmd: String, val label: String, val timeout: Long)
        val cases = listOf(
            Case({ s.amiibo("dump") }, "nfc_amiibo dump", "Amiibo (dump)", NfcToolsService.PROMPT_TIMEOUT_MS),
            Case({ s.nested() }, "mifare_nested", "Nested attack", NfcToolsService.PROMPT_TIMEOUT_MS),
            Case({ s.mfkey32() }, "mifare_mfkey32", "mfkey32 attack", NfcToolsService.PROMPT_TIMEOUT_MS),
            Case({ s.srixRead() }, "srix_read 8", "SRIX read", 8 * 1_000L + NfcToolsService.SECS_MARGIN_MS),
            Case({ s.rfid125Read(12) }, "rfid125_read 12", "125kHz read", 12 * 1_000L + NfcToolsService.SECS_MARGIN_MS),
        )
        cases.forEachIndexed { i, c ->
            c.go()
            assertEquals("${c.label} running..." to true, s.advStatus.value to s.advBusy.value)
            finish(i, i % 2 == 0, if (i == 0) "" else "x$i") { s.advBusy.value }
            assertEquals(if (i % 2 == 0) (if (i == 0) "${c.label} done." else "x$i") else "x$i", s.advStatus.value)
        }
        assertEquals(cases.map { it.cmd to it.timeout }, runner.calls.toList())
    }

    @Test(timeout = 5_000) fun srixDefaultDoneTextAndRfidFailText() = runBlocking {
        val s = svc()
        s.srixRead()
        finish(0, true, "") { s.advBusy.value }
        assertEquals("SRIX read done.", s.advStatus.value)
        s.rfid125Read()
        finish(1, false, "") { s.advBusy.value }
        assertEquals("125kHz read failed.", s.advStatus.value)
    }

    @Test(timeout = 5_000) fun advancedTilesSupersedeEachOther() = runBlocking {
        val s = svc()
        s.nested()
        until { runner.calls.size == 1 }
        s.mfkey32()
        until { runner.calls.size == 2 }
        assertEquals("mfkey32 attack running..." to true, s.advStatus.value to s.advBusy.value)
        runner.reply(0, true, "stale nested")
        delay(20)
        assertEquals("mfkey32 attack running..." to true, s.advStatus.value to s.advBusy.value)
        finish(1, true, "keys") { s.advBusy.value }
        assertEquals("keys", s.advStatus.value)
    }

    @Test(timeout = 5_000) fun fourLinesAreIndependent() = runBlocking {
        val s = svc()
        s.pushNdef("url", "x")
        s.readEmv()
        s.writeUid("ab")
        s.nested()
        until { runner.calls.size == 4 }
        assertEquals("Pushing NDEF (url) - tap a device...", s.ndefStatus.value)
        assertEquals("Reading card - hold it on the reader...", s.emvStatus.value)
        assertEquals("Writing UID ab...", s.uidStatus.value)
        assertEquals("Nested attack running...", s.advStatus.value)
        // Completing the push leaves the other three pending lines untouched.
        runner.reply(0, true, "pushed!")
        until { !s.ndefBusy.value }
        assertEquals("pushed!", s.ndefStatus.value)
        assertTrue(s.emvBusy.value && s.uidBusy.value && s.advBusy.value)
        assertEquals("Reading card - hold it on the reader...", s.emvStatus.value)
        assertEquals("Writing UID ab...", s.uidStatus.value)
        assertEquals("Nested attack running...", s.advStatus.value)
        // Release the sticky gates so runBlocking's children can finish.
        for (i in 1..3) runner.reply(i, true, "")
        until { !s.emvBusy.value && !s.uidBusy.value && !s.advBusy.value }
    }

    @Test(timeout = 5_000) fun stopAllCancelsEveryLine() = runBlocking {
        val s = svc()
        s.pushNdef("url", "x"); s.readEmv(); s.writeUid("ab"); s.nested()
        until { runner.calls.size == 4 }
        s.stopAll()
        val stopped = "Stopped." to false
        assertEquals(stopped, s.ndefStatus.value to s.ndefBusy.value)
        assertEquals(stopped, s.emvStatus.value to s.emvBusy.value)
        assertEquals(stopped, s.uidStatus.value to s.uidBusy.value)
        assertEquals(stopped, s.advStatus.value to s.advBusy.value)
        for (i in 0..3) runner.reply(i, true, "late")
        delay(20)
        assertEquals("Stopped.", s.advStatus.value)
    }

    @Test(timeout = 5_000) fun notConnectedSendsNothingOnEveryEntry() = runBlocking {
        connected = false
        val s = svc()
        val connect = "Connect the device over USB first."
        s.pushNdef("url", "x"); assertEquals(connect, s.ndefStatus.value)
        s.readEmv(); assertEquals(connect, s.emvStatus.value)
        s.writeUid("ab"); assertEquals(connect, s.uidStatus.value)
        s.srixRead(); assertEquals(connect, s.advStatus.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }
}
