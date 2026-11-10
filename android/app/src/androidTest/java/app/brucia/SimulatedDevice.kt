package app.brucia

import app.brucia.protocol.Kind
import app.brucia.transport.Transport
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A companion-mode device simulated in the test process, behind the app's own [Transport]
 * interface (the seam UsbTransport implements). Mirrors tools/mock_device.py for the frames the
 * flow test needs: HELLO -> CAPS, PING -> PONG, CMD -> ACK, SUB -> ACK. [leaveCompanion] makes it
 * behave like Bruce dropping back to its serial CLI with USB still open: it prints the CLI echo
 * and ignores frames until the app sends the raw `companion` nudge.
 *
 * Not a substitute for a real board: no radio, no USB stack, no firmware timing.
 */
internal class SimulatedDevice : Transport {
    private val lock = Object()
    private val toApp = ByteArrayOutputStream()
    @Volatile private var open = true
    @Volatile private var inCompanion = true

    val hellos = AtomicInteger()
    val nudges = AtomicInteger()
    val pings = AtomicInteger()
    /** Every SUB body the app sent, in order: stream to on/off. */
    val subs = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val commands = CopyOnWriteArrayList<String>()

    override val isOpen: Boolean get() = open
    override val kind: String get() = "usb"

    override fun write(bytes: ByteArray) {
        check(open) { "write after close" }
        val text = String(bytes, Charsets.ISO_8859_1)
        if (text.startsWith("companion")) { // raw CLI line, not a frame
            nudges.incrementAndGet()
            inCompanion = true
            return
        }
        var at = 0
        while (at + 4 <= bytes.size) {
            val len = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
            require(len > 0 && at + 4 + len <= bytes.size) { "app wrote a partial frame" }
            handle(JSONObject(String(bytes, at + 4, len, Charsets.UTF_8)))
            at += 4 + len
        }
    }

    private fun handle(msg: JSONObject) {
        if (!inCompanion) return // the serial CLI reads frames as garbage text
        val id = msg.optInt("id")
        val d = msg.optJSONObject("d") ?: JSONObject()
        when (msg.optInt("t")) {
            Kind.HELLO -> { hellos.incrementAndGet(); send(Kind.CAPS, id, caps()) }
            Kind.PING -> { pings.incrementAndGet(); send(Kind.PONG, id, JSONObject().put("battery", 77)) }
            Kind.CMD -> {
                commands += d.optString("cmd")
                send(Kind.ACK, id, JSONObject().put("ok", true).put("out", ""))
            }
            Kind.SUB -> {
                subs += d.optString("stream") to d.optBoolean("on")
                send(Kind.ACK, id, JSONObject().put("ok", true))
            }
        }
    }

    /** Leave companion mode with USB still open, like the firmware's CLI fallback. */
    fun leaveCompanion() {
        inCompanion = false
        push("COMMAND: companion exit\n".toByteArray(Charsets.US_ASCII))
    }

    fun lastSub(stream: String): Boolean? = subs.lastOrNull { it.first == stream }?.second

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (open && toApp.size() == 0) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return 0
                lock.wait(left)
            }
            if (!open) return 0
            val pending = toApp.toByteArray()
            val n = minOf(buf.size, pending.size)
            System.arraycopy(pending, 0, buf, 0, n)
            toApp.reset()
            toApp.write(pending, n, pending.size - n)
            return n
        }
    }

    override fun close() {
        synchronized(lock) { open = false; lock.notifyAll() }
    }

    private fun send(kind: Int, id: Int, d: JSONObject) {
        val payload = "{\"v\":0,\"t\":$kind,\"id\":$id,\"d\":$d}".toByteArray(Charsets.UTF_8)
        val n = payload.size
        push(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + payload)
    }

    private fun push(bytes: ByteArray) {
        synchronized(lock) { toApp.write(bytes); lock.notifyAll() }
    }

    private fun caps() = JSONObject()
        .put("board", BOARD).put("chip", "esp32-s3").put("fw", "sim").put("proto", 0)
        .put("battery", 77)
        .put("modules", JSONArray(listOf("wifi", "ble")))
        .put("transports", JSONArray(listOf("usb")))
        .put("caps", JSONObject().put("wifi", true).put("ble", true))
        .put("features", JSONObject())

    companion object {
        const val BOARD = "Simulated T-Embed"
    }
}
