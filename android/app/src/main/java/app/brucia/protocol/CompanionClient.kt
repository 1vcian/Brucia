package app.brucia.protocol

import app.brucia.transport.Transport
import org.json.JSONObject

/**
 * Speaks the companion wire protocol over a [Transport]: length-prefixed
 * (uint32 LE) JSON envelopes {v, t, id, d}. Mirrors the firmware side.
 */
class CompanionClient(private val transport: Transport) {

    private val chunk = ByteArray(4096)
    private var buffer = ByteArray(0)

    // --- outgoing ---

    private fun send(kind: Int, id: Int, body: JSONObject) {
        val env = JSONObject()
            .put("v", PROTO_VERSION)
            .put("t", kind)
            .put("id", id)
            .put("d", body)
        val payload = env.toString().toByteArray(Charsets.UTF_8)
        val len = payload.size
        val head = byteArrayOf(
            (len and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 24) and 0xFF).toByte(),
        )
        transport.write(head + payload)
    }

    fun hello() = send(Kind.HELLO, 1, JSONObject().put("app", "Brucia").put("appVersion", "0.1.0"))
    fun ping(id: Int = 2) = send(Kind.PING, id, JSONObject())
    fun cmd(command: String, id: Int = 3) = send(Kind.CMD, id, JSONObject().put("cmd", command))
    fun sub(stream: String, on: Boolean, id: Int = 4) =
        send(Kind.SUB, id, JSONObject().put("stream", stream).put("on", on))

    fun subWifiAp(on: Boolean) = sub("wifi.ap", on)

    // --- incoming ---

    /** Blocking read of one envelope; null if nothing complete within [timeoutMs]. */
    fun readMessage(timeoutMs: Int): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            extractFrame()?.let { return it }
            val remaining = (deadline - System.currentTimeMillis())
            if (remaining <= 0) return extractFrame()
            val n = transport.read(chunk, remaining.coerceIn(1, timeoutMs.toLong()).toInt())
            if (n > 0) buffer += chunk.copyOf(n)
        }
    }

    private fun extractFrame(): JSONObject? {
        if (buffer.size < 4) return null
        val len = (buffer[0].toInt() and 0xFF) or
            ((buffer[1].toInt() and 0xFF) shl 8) or
            ((buffer[2].toInt() and 0xFF) shl 16) or
            ((buffer[3].toInt() and 0xFF) shl 24)
        if (len <= 0 || len > (1 shl 20)) {
            buffer = ByteArray(0) // desync guard
            return null
        }
        if (buffer.size < 4 + len) return null
        val payload = buffer.copyOfRange(4, 4 + len)
        buffer = buffer.copyOfRange(4 + len, buffer.size)
        return try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
