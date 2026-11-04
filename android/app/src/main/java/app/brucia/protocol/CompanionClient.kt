package app.brucia.protocol

import app.brucia.BuildConfig
import app.brucia.transport.Transport
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks the companion wire protocol over a [Transport]: length-prefixed
 * (uint32 LE) JSON envelopes {v, t, id, d}. Mirrors the firmware side.
 * [counters] records frames in/out and every frame the decoder skips (Diagnostics).
 */
class CompanionClient(private val transport: Transport, val counters: LinkCounters = LinkCounters()) {

    private val chunk = ByteArray(4096)
    private var buffer = ByteArray(0)

    // Monotonic request ids so in-flight commands can be correlated with their
    // ACK/DATA responses. Starts high to avoid clashing with the fixed ids below.
    private companion object {
        // Keep IDs unique across reconnects, so a late local failure cannot resolve
        // a command belonging to a newer transport session.
        val idCounter = AtomicInteger(100)
    }

    /** Next unique request id for a command that needs its response correlated. */
    fun nextId(): Int = idCounter.incrementAndGet()

    // --- outgoing ---

    @Synchronized
    private fun send(kind: Int, id: Int, body: JSONObject) {
        val env = JSONObject()
            .put("v", PROTO_VERSION)
            .put("t", kind)
            .put("id", id)
            .put("d", body)
        val payload = env.toString().toByteArray(Charsets.UTF_8)
        val len = payload.size
        require(len <= 8192) { "Command exceeds firmware frame limit (8192 bytes)" }
        val head = byteArrayOf(
            (len and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 24) and 0xFF).toByte(),
        )
        transport.write(head + payload)
        counters.noteFrameSent()
    }

    fun hello() = send(Kind.HELLO, 1, JSONObject().put("app", "Brucia").put("appVersion", BuildConfig.VERSION_NAME))
    fun ping(id: Int = 2) = send(Kind.PING, id, JSONObject())
    /** Send a command; returns the id used so the caller can match the response. */
    fun cmd(command: String, id: Int = nextId()): Int {
        send(Kind.CMD, id, JSONObject().put("cmd", command))
        return id
    }
    /** Structured CMD body used by firmware handlers such as speaker PCM. */
    fun cmdFields(command: String, fields: JSONObject, id: Int = nextId()): Int {
        send(Kind.CMD, id, JSONObject(fields.toString()).put("cmd", command))
        return id
    }
    fun sub(stream: String, on: Boolean, id: Int = 4) =
        send(Kind.SUB, id, JSONObject().put("stream", stream).put("on", on))

    /** SUB variant carrying optional monitor parameters: {stream, on, channel?, bssid?}. */
    fun sub(stream: String, on: Boolean, channel: Int?, bssid: String?, id: Int = 4) {
        val body = JSONObject().put("stream", stream).put("on", on)
        if (channel != null) body.put("channel", channel)
        if (!bssid.isNullOrEmpty()) body.put("bssid", bssid)
        send(Kind.SUB, id, body)
    }

    fun subWifiAp(on: Boolean) = sub("wifi.ap", on)

    // --- incoming ---

    /** Blocking read of one envelope; null if nothing complete within [timeoutMs]. */
    fun readMessage(timeoutMs: Int): JSONObject? {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        while (true) {
            extractFrame()?.let { return it }
            if (!transport.isOpen) return null
            val remaining = (deadline - System.nanoTime() + 999_999L) / 1_000_000L
            if (remaining <= 0) return extractFrame()
            val n = transport.read(chunk, remaining.coerceIn(1, timeoutMs.toLong()).toInt())
            if (n > 0) buffer += chunk.copyOf(n)
        }
    }

    private fun extractFrame(): JSONObject? {
        while (buffer.size >= 4) {
            val len = (buffer[0].toInt() and 0xFF) or
                ((buffer[1].toInt() and 0xFF) shl 8) or
                ((buffer[2].toInt() and 0xFF) shl 16) or
                ((buffer[3].toInt() and 0xFF) shl 24)
            if (len <= 0 || len > 8192) {
                buffer = ByteArray(0) // Invalid length: frame boundary is unknown.
                counters.noteInvalidLength()
                throw java.io.IOException("Invalid companion frame length: $len")
            }
            if (buffer.size < 4 + len) return null
            val payload = buffer.copyOfRange(4, 4 + len)
            buffer = buffer.copyOfRange(4 + len, buffer.size)
            try {
                val message = JSONObject(String(payload, Charsets.UTF_8))
                counters.noteFrameReceived()
                return message
            } catch (_: Exception) {
                // The length was valid, so the next boundary is known. Drain any
                // complete buffered frames before waiting for more transport data.
                // Skipped, but counted: Diagnostics shows how many were lost.
                counters.noteMalformedFrame()
            }
        }
        return null
    }
}
