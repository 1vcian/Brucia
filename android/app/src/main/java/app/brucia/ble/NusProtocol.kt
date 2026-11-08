package app.brucia.ble

import java.util.UUID

/**
 * Nordic UART Service as the firmware's BLE API exposes it (Config > Advanced > Toggle BLE API):
 * write a CLI line to [RX], read its output from [TX] notifications. The firmware runs commands only
 * for a phone bonded with the passkey it shows, so the console pairs before sending anything.
 * Pure helpers here; the Android GATT side is [BleConsole].
 */
object NusProtocol {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The firmware drops a line longer than this (ble_line_buffer.h MAX_LINE). */
    const val MAX_LINE_BYTES = 512
    /** ATT payload on the default 23-byte MTU. */
    const val MIN_CHUNK = 20

    /** The line to send for [text] (one command, '\n'-terminated), or null with nothing to send. */
    fun commandLine(text: String): String? {
        val t = text.trimEnd('\r', '\n').trim()
        if (t.isEmpty() || t.any { it == '\n' || it == '\r' }) return null
        if (t.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
        return "$t\n"
    }

    /** Why [text] cannot be sent, or null. */
    fun rejection(text: String): String? {
        val t = text.trimEnd('\r', '\n').trim()
        return when {
            t.isEmpty() -> "Nothing to send."
            t.any { it == '\n' || it == '\r' } -> "One command per line."
            t.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES -> "Command too long (max $MAX_LINE_BYTES bytes)."
            else -> null
        }
    }

    /** [line] split into writes that fit the negotiated [mtu] (payload = mtu - 3). */
    fun chunks(line: String, mtu: Int): List<ByteArray> {
        val bytes = line.toByteArray(Charsets.UTF_8)
        val size = maxOf(MIN_CHUNK, mtu - 3)
        return (bytes.indices step size).map { bytes.copyOfRange(it, minOf(it + size, bytes.size)) }
    }
}

/**
 * Reassembles TX notifications (arbitrary chunks, UTF-8 possibly split mid-character) into lines.
 * A run without a newline longer than [maxPending] is emitted as it is, so output never stalls.
 */
class NusLineAssembler(private val maxPending: Int = 8192) {
    private val pending = java.io.ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<String> {
        val out = ArrayList<String>()
        for (b in chunk) {
            if (b == '\n'.code.toByte()) {
                out += pending.toString(Charsets.UTF_8.name()).trimEnd('\r')
                pending.reset()
            } else {
                pending.write(b.toInt())
                if (pending.size() >= maxPending) {
                    out += pending.toString(Charsets.UTF_8.name())
                    pending.reset()
                }
            }
        }
        return out
    }

    /** The partial line not yet ended by a newline (shown as the console's last line). */
    fun partial(): String = pending.toString(Charsets.UTF_8.name()).trimEnd('\r')

    fun clear() = pending.reset()
}
