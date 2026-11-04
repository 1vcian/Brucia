package app.brucia

import app.brucia.nfc.NfcTagService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Reuses GateRunner / settle from DeviceOperationTest; single-threaded, no wall-clock waits. */
class NfcTagServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    private val saved = CopyOnWriteArrayList<Triple<String, String, String>>()
    private var connected = true

    private fun CoroutineScope.svc() = NfcTagService(this, runner, { connected }) { uid, type, out -> saved += Triple(uid, type, out) }

    private suspend fun answer(i: Int, ok: Boolean, out: String) {
        settle()
        assertTrue("call $i not sent", runner.calls.size > i)
        runner.reply(i, ok, out)
        settle()
    }

    @Test(timeout = 5_000) fun readShowsOutputAndSavesTagWithUid() = runBlocking {
        val s = svc()
        s.read()
        assertTrue(s.busy.value)
        assertNull(s.last.value)
        answer(0, true, TAG)
        assertEquals(TAG, s.last.value)
        assertFalse(s.busy.value)
        assertEquals(listOf(Triple("04 A1 B2 C3", "MIFARE Ultralight", TAG)), saved.toList())
        assertEquals(listOf("rfid read" to 650_000L), runner.calls.toList())
    }

    @Test(timeout = 5_000) fun failedOrUidlessReadShowsOutputButSavesNothing() = runBlocking {
        val s = svc()
        s.read()
        answer(0, false, "Read failed: timeout")
        assertEquals("Read failed: timeout", s.last.value)
        s.read()
        answer(1, true, "Type: unknown")
        assertEquals("Type: unknown", s.last.value)
        s.read()
        answer(2, false, TAG) // an ERR carrying tag text is still not saved
        assertTrue(saved.isEmpty())
    }

    @Test(timeout = 5_000) fun infoAndNdefShareTheLastLineAndNeverSave() = runBlocking {
        val s = svc()
        s.info()
        answer(0, true, TAG)
        assertEquals(TAG, s.last.value)
        s.ndef()
        answer(1, false, "NDEF: write failed")
        assertEquals("NDEF: write failed", s.last.value)
        assertTrue(saved.isEmpty())
        assertEquals(listOf("rfid info", "rfid ndef"), runner.cmds)
    }

    @Test(timeout = 5_000) fun supersededReplyNeverOverwritesOrSaves() = runBlocking {
        val s = svc()
        s.read()
        settle()
        s.info()
        settle()
        runner.reply(0, true, TAG)
        settle()
        assertNull(s.last.value)
        assertTrue(saved.isEmpty())
        assertTrue(s.busy.value)
        answer(1, true, "No tag data. Run 'rfid read' first.")
        assertEquals("No tag data. Run 'rfid read' first.", s.last.value)
    }

    @Test(timeout = 5_000) fun stopDropsTheLateReply() = runBlocking {
        val s = svc()
        s.info()
        answer(0, true, "first")
        s.read()
        settle()
        s.stop()
        assertEquals("Stopped." to false, s.status.value to s.busy.value)
        runner.reply(1, true, TAG)
        settle()
        assertEquals("first", s.last.value)
        assertTrue(saved.isEmpty())
    }

    @Test(timeout = 5_000) fun notConnectedSendsNothingAndKeepsLast() = runBlocking {
        connected = false
        val s = svc()
        s.read(); s.info(); s.ndef()
        settle()
        assertTrue(runner.calls.isEmpty())
        assertNull(s.last.value)
        assertEquals("Connect the device over USB first." to false, s.status.value to s.busy.value)
    }

    private companion object {
        val TAG = """
            Waiting for tag (5000 ms)...
            UID:   04 A1 B2 C3
            Type:  MIFARE Ultralight
            SAK:   00
        """.trimIndent()
    }
}
