package app.brucia.protocol

import app.brucia.BuildConfig
import app.brucia.transport.Transport
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks the companion wire protocol over a [Transport]: length-prefixed
 * (uint32 LE) JSON envelopes {v, t, id, d}. Mirrors the firmware side.
 * [counters] records frames in/out and every frame or byte the decoder skips (Diagnostics).
 *
 * Every device payload starts with the envelope prefix `{"v":`. Text the firmware prints outside
 * a frame (a module debug line, an IDF log) is skipped: the decoder resyncs on the next 4-byte
 * length in range followed by that prefix (proto/PROTOCOL.md "Resynchronisation").
 */
class CompanionClient(private val transport: Transport, val counters: LinkCounters = LinkCounters()) {

    private val chunk = ByteArray(4096)
    private var buffer = ByteArray(0)
    /** True while skipping noise; one noise run is counted once however it arrives. */
    private var resyncing = false

    /**
     * Largest payload [send] writes. The protocol maximum until CAPS reports `rx_max`
     * ([FrameBudget.sendLimit]); a larger frame would not fit the device's RX buffer.
     */
    @Volatile var sendLimit: Int = FrameBudget.MAX_FRAME

    // Monotonic request ids so in-flight commands can be correlated with their
    // ACK/DATA responses. Starts high to avoid clashing with the fixed ids below.
    private companion object {
        // Keep IDs unique across reconnects, so a late local failure cannot resolve
        // a command belonging to a newer transport session.
        val idCounter = AtomicInteger(100)

        /** Every device payload starts with these bytes: beginEnvelope() writes "v" first. */
        val ENVELOPE_PREFIX = "{\"v\":".toByteArray(Charsets.US_ASCII)
    }

    /**
     * Android's org.json writes '/' as "\/" (an optional JSON escape), which grows base64 by up to
     * 2x. Undo exactly those escapes, walking escape pairs left to right so "\\" stays intact.
     */
    internal fun unescapeSlashes(json: String): String {
        if (json.indexOf("\\/") < 0) return json
        val out = StringBuilder(json.length)
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                if (next != '/') out.append(c)
                out.append(next)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Next unique request id for a command that needs its response correlated. */
    fun nextId(): Int = idCounter.incrementAndGet()

    // --- outgoing ---

    @Synchronized
    private fun send(kind: Int, id: Int, body: JSONObject) {
        // Envelope keys in the firmware's order ("v" first) whatever the JSON library's map order.
        val env = "{\"v\":$PROTO_VERSION,\"t\":$kind,\"id\":$id,\"d\":$body}"
        val payload = unescapeSlashes(env).toByteArray(Charsets.UTF_8)
        val len = payload.size
        val limit = sendLimit
        require(len <= limit) {
            if (limit < FrameBudget.MAX_FRAME) "Command exceeds the device receive buffer ($len > $limit bytes)"
            else "Command exceeds firmware frame limit (${FrameBudget.MAX_FRAME} bytes)"
        }
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
        while (sync()) {
            val len = lengthAt(0)
            if (buffer.size < 4 + len) return null
            val payload = buffer.copyOfRange(4, 4 + len)
            buffer = buffer.copyOfRange(4 + len, buffer.size)
            resyncing = false
            try {
                val message = JSONObject(String(payload, Charsets.UTF_8))
                counters.noteFrameReceived()
                return message
            } catch (_: Exception) {
                // The header and envelope prefix were valid, so the next boundary is known.
                // Drain any complete buffered frames before waiting for more transport data.
                // Skipped, but counted: Diagnostics shows how many were lost.
                counters.noteMalformedFrame()
            }
        }
        return null
    }

    private fun lengthAt(i: Int): Int = (buffer[i].toInt() and 0xFF) or
        ((buffer[i + 1].toInt() and 0xFF) shl 8) or
        ((buffer[i + 2].toInt() and 0xFF) shl 16) or
        ((buffer[i + 3].toInt() and 0xFF) shl 24)

    private fun plausibleAt(i: Int): Boolean {
        val len = lengthAt(i)
        return len >= ENVELOPE_PREFIX.size && len <= FrameBudget.MAX_FRAME
    }

    private fun prefixAt(i: Int): Boolean {
        for (k in ENVELOPE_PREFIX.indices) if (buffer[i + k] != ENVELOPE_PREFIX[k]) return false
        return true
    }

    /**
     * Align [buffer] on a frame start: a plausible length followed by the envelope prefix. True
     * when it is aligned, false when more bytes are needed. Skipped bytes are counted as noise; an
     * implausible length (0 or above the limit) at a frame boundary is also counted once.
     */
    private fun sync(): Boolean {
        val need = 4 + ENVELOPE_PREFIX.size
        while (buffer.size >= 4) {
            val plausible = plausibleAt(0)
            if (plausible && buffer.size < need) return false
            if (plausible && prefixAt(4)) return true
            if (!resyncing) {
                resyncing = true
                if (!plausible) counters.noteInvalidLength()
            }
            var next = -1
            var p = 4 + 1 // first candidate: a header at index 1, its prefix at 5
            while (p + ENVELOPE_PREFIX.size <= buffer.size) {
                if (prefixAt(p) && plausibleAt(p - 4)) { next = p - 4; break }
                p++
            }
            val drop = if (next > 0) next else buffer.size - (need - 1)
            if (drop <= 0) return false // a frame start may straddle the end: wait for more
            counters.noteNoise(drop)
            buffer = buffer.copyOfRange(drop, buffer.size)
        }
        return false
    }
}
