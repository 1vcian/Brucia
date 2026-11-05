package app.brucia

import app.brucia.ibutton.IButtonService
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandRunner
import app.brucia.session.CommandSession
import app.brucia.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Decodes each written frame (4-byte LE length + {v,t,id,d}); [onWrite] can reply inline. */
private class IButtonTransport : Transport {
    val frames = ArrayList<JSONObject>()
    override var isOpen = true
    var onWrite: (JSONObject) -> Unit = {}
    override fun write(bytes: ByteArray) {
        val len = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)
        val env = JSONObject(String(bytes, 4, len, Charsets.UTF_8))
        frames += env
        onWrite(env)
    }
    override fun read(buf: ByteArray, timeoutMs: Int) = 0
    override fun close() { isOpen = false }
}

private fun ackOf(id: Int, ok: Boolean, out: String) = JSONObject().put("v", 0).put("t", Kind.ACK)
    .put("id", id).put("d", JSONObject().put("ok", ok).put("out", out))

class IButtonServiceTest {
    private var t = IButtonTransport()
    private var session = CommandSession(CompanionClient(t), t)
    private var connected = true
    private var iface = true
    private val runner = CommandRunner { cmd, timeoutMs -> session.await(cmd, timeoutMs) }

    private fun CoroutineScope.service(timeoutMs: Long = 5_000) =
        IButtonService(this, runner, { connected }, { iface }, timeoutMs)

    private fun sentCmds() = t.frames.map { it.getJSONObject("d").getString("cmd") }
    private fun replyWith(ok: Boolean, out: String) { t.onWrite = { session.route(ackOf(it.getInt("id"), ok, out)) } }

    /** Bounded polling: lets the service coroutine run without fixed sleeps. */
    private suspend fun until(cond: () -> Boolean) = withTimeout(2_000) { while (!cond()) delay(1) }

    @Test fun readSuccessShowsId() = runVirtual {
        replyWith(true, "id=0123456789ABCDEF")
        val svc = service()
        svc.read()
        assertEquals("Reading - touch an iButton to the probe...", svc.status.value)
        assertTrue(svc.busy.value)
        until { !svc.busy.value }
        assertEquals("id=0123456789ABCDEF", svc.status.value)
        assertEquals(listOf("ibutton_read"), sentCmds())
    }

    @Test fun readWithoutKeyShowsFirmwareText() = runVirtual {
        replyWith(false, "no iButton")
        val svc = service()
        svc.read()
        until { !svc.busy.value }
        assertEquals("no iButton", svc.status.value)
    }

    @Test fun writeSendsNormalizedId() = runVirtual {
        replyWith(true, "")
        val svc = service()
        svc.write(" 01:23:45:67 89ab cdef ")
        assertEquals("Writing 0123456789abcdef...", svc.status.value)
        until { !svc.busy.value }
        assertEquals("Wrote 0123456789abcdef.", svc.status.value)
        assertEquals(listOf("ibutton_write 0123456789abcdef"), sentCmds())
    }

    @Test fun invalidOrBlankIdIsRejectedLocally() = runVirtual {
        val svc = service()
        svc.write("0123456789ABCDEZ")
        assertEquals(IButtonService.INVALID_ID, svc.status.value)
        svc.write("0123")
        assertEquals(IButtonService.INVALID_ID, svc.status.value)
        svc.write(" : ")
        assertEquals("Enter an id to write.", svc.status.value)
        assertFalse(svc.busy.value)
        assertTrue(t.frames.isEmpty())
    }

    @Test fun firmwareRejectionTextIsShown() = runVirtual {
        replyWith(false, "invalid id (need 16 hex chars)")
        val svc = service()
        svc.write("0123456789ABCDEF")
        until { !svc.busy.value }
        assertEquals("invalid id (need 16 hex chars)", svc.status.value)
    }

    @Test fun notConnectedOrNoInterfaceSendsNothing() = runVirtual {
        val svc = service()
        connected = false
        svc.read()
        assertEquals("Connect the device over USB first.", svc.status.value)
        svc.write("0123456789ABCDEF")
        assertEquals("Connect the device over USB first.", svc.status.value)
        connected = true
        iface = false
        svc.read()
        assertEquals("No confirmed iButton interface", svc.status.value)
        svc.write("0123456789ABCDEF")
        assertEquals("No confirmed iButton interface", svc.status.value)
        assertFalse(svc.busy.value)
        assertTrue(t.frames.isEmpty())
    }

    @Test fun timeoutReportsNoResponse() = runVirtual {
        val svc = service(timeoutMs = 50)
        svc.read()
        until { !svc.busy.value }
        assertEquals("No response from the device.", svc.status.value)
        assertFalse(session.hasPending)
    }

    @Test fun stopCancelsAndIgnoresLateReply() = runVirtual {
        val svc = service()
        svc.stop()
        assertEquals("", svc.status.value)
        svc.read()
        until { t.frames.size == 1 }
        svc.stop()
        assertEquals("Stopped.", svc.status.value)
        assertFalse(svc.busy.value)
        until { !session.hasPending }
        session.route(ackOf(t.frames[0].getInt("id"), true, "id=LATE"))
        delay(20)
        assertEquals("Stopped.", svc.status.value)
        assertFalse(svc.busy.value)
    }

    @Test fun secondReadSupersedesFirst() = runVirtual {
        val svc = service()
        svc.read()
        until { t.frames.size == 1 }
        svc.read()
        until { t.frames.size == 2 }
        val (first, second) = t.frames.map { it.getInt("id") }
        session.route(ackOf(first, true, "id=OLD"))
        delay(20)
        assertEquals("Reading - touch an iButton to the probe...", svc.status.value)
        assertTrue(svc.busy.value)
        session.route(ackOf(second, true, "id=NEW"))
        until { !svc.busy.value }
        assertEquals("id=NEW", svc.status.value)
    }

    @Test fun sessionCloseThenReconnectWorks() = runVirtual {
        val svc = service()
        svc.read()
        until { t.frames.size == 1 }
        session.close()
        until { !svc.busy.value }
        assertEquals("Disconnected", svc.status.value)
        t = IButtonTransport()
        session = CommandSession(CompanionClient(t), t)
        replyWith(true, "id=00112233445566FF")
        svc.read()
        until { !svc.busy.value }
        assertEquals("id=00112233445566FF", svc.status.value)
    }
}
