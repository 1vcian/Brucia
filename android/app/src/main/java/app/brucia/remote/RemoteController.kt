package app.brucia.remote

import app.brucia.transport.Transport

/**
 * Remote control of the real Bruce UI over raw serial (no companion framing): sends
 * `nav <dir>` to drive the menus and `display start` to receive the TFT mirror stream,
 * which [TftProtocol] + [TftRenderer] turn into a live screen. Used while the device is
 * on its normal UI (its serial CLI task runs in the background).
 *
 * The caller owns the read loop: feed it bytes with [onBytes]; it renders and reports
 * a frame counter so the UI can recompose.
 */
class RemoteController(private val transport: Transport) {
    val renderer = TftRenderer()
    private val buffer = ByteArrayBuilder()
    @Volatile var frame: Long = 0L
        private set

    fun startMirror() = sendLine("display start")
    fun stopMirror() = sendLine("display stop")

    fun nav(dir: String) = sendLine("nav $dir")

    /** Run any raw CLI line (this is how the app reaches every Bruce command). */
    fun cli(line: String) = sendLine(line)

    private fun sendLine(line: String) {
        try { transport.write((line + "\r\n").toByteArray()) } catch (e: Exception) {
            app.brucia.BruciaLog.w("Remote", "write failed for '$line'", e)
        }
    }

    /** Feed received bytes; extracts mirror packets and renders them. */
    fun onBytes(data: ByteArray, len: Int) {
        buffer.append(data, len)
        val (ops, consumed) = TftProtocol.parse(buffer.array(), buffer.size())
        if (consumed > 0) {
            buffer.consume(consumed)
            if (ops.isNotEmpty()) { renderer.applyAll(ops); frame++ }
        }
        buffer.compactIfLarge()
    }
}

/** Small growable byte buffer that keeps an unparsed tail between reads. */
private class ByteArrayBuilder {
    private var buf = ByteArray(8192)
    private var len = 0
    fun append(data: ByteArray, n: Int) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(data, 0, buf, len, n)
        len += n
    }
    fun array(): ByteArray = buf
    fun size(): Int = len
    fun consume(n: Int) {
        if (n <= 0) return
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }
    // Avoid unbounded growth if a stream has no valid packets (e.g. text-only).
    fun compactIfLarge() { if (len > 32768) { System.arraycopy(buf, len - 4096, buf, 0, 4096); len = 4096 } }
}
