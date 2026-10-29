package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.transport.Transport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake byte pipe: queues inbound chunks, records outbound bytes. */
private class FakeTransport : Transport {
    val inbound = ArrayDeque<ByteArray>()
    val written = ArrayList<Byte>()
    override val isOpen = true
    override fun write(bytes: ByteArray) { written.addAll(bytes.toList()) }
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val chunk = inbound.removeFirstOrNull() ?: return 0
        val n = minOf(chunk.size, buf.size)
        for (i in 0 until n) buf[i] = chunk[i]
        if (n < chunk.size) inbound.addFirst(chunk.copyOfRange(n, chunk.size))
        return n
    }
    override fun close() {}
}

private fun frame(obj: JSONObject): ByteArray {
    val payload = obj.toString().toByteArray(Charsets.UTF_8)
    val len = payload.size
    val head = byteArrayOf(
        (len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte(),
        ((len ushr 16) and 0xFF).toByte(), ((len ushr 24) and 0xFF).toByte(),
    )
    return head + payload
}

private fun envelope(kind: Int, id: Int, d: JSONObject) =
    JSONObject().put("v", 0).put("t", kind).put("id", id).put("d", d)

class ProtocolTest {

    @Test fun helloIsFramedCorrectly() {
        val t = FakeTransport()
        CompanionClient(t).hello()
        val bytes = t.written.toByteArray()
        assertTrue(bytes.size > 4)
        val len = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)
        assertEquals(len, bytes.size - 4)
        val obj = JSONObject(String(bytes, 4, len, Charsets.UTF_8))
        assertEquals(Kind.HELLO, obj.getInt("t"))
        assertEquals("Brucia", obj.getJSONObject("d").getString("app"))
    }

    @Test fun readsOneFrame() {
        val t = FakeTransport()
        t.inbound.add(frame(envelope(Kind.CAPS, 1, JSONObject().put("board", "x").put("chip", "esp32-s3"))))
        val msg = CompanionClient(t).readMessage(500)
        assertNotNull(msg)
        assertEquals(Kind.CAPS, msg!!.getInt("t"))
        assertEquals("esp32-s3", msg.getJSONObject("d").getString("chip"))
    }

    @Test fun reassemblesChunkedFrame() {
        val t = FakeTransport()
        val full = frame(envelope(Kind.EVENT, 0, JSONObject().put("ev", "wifi.ap")))
        // deliver in three awkward chunks (split header and payload)
        t.inbound.add(full.copyOfRange(0, 2))
        t.inbound.add(full.copyOfRange(2, 7))
        t.inbound.add(full.copyOfRange(7, full.size))
        val msg = CompanionClient(t).readMessage(500)
        assertNotNull(msg)
        assertEquals("wifi.ap", msg!!.getJSONObject("d").getString("ev"))
    }

    @Test fun parsesTwoFramesInSequence() {
        val t = FakeTransport()
        val c = CompanionClient(t)
        t.inbound.add(frame(envelope(Kind.PONG, 2, JSONObject())))
        t.inbound.add(frame(envelope(Kind.ACK, 3, JSONObject().put("ok", true).put("out", "done"))))
        val m1 = c.readMessage(500); val m2 = c.readMessage(500)
        assertEquals(Kind.PONG, m1!!.getInt("t"))
        assertEquals("done", m2!!.getJSONObject("d").getString("out"))
    }

    @Test fun timesOutWhenNoData() {
        assertNull(CompanionClient(FakeTransport()).readMessage(50))
    }
}
