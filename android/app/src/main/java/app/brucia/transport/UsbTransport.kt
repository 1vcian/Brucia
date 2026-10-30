package app.brucia.transport

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort

/** USB-CDC transport backed by usb-serial-for-android. */
class UsbTransport(
    connection: UsbDeviceConnection,
    private val port: UsbSerialPort,
    baudRate: Int = 115200,
) : Transport {

    // Set when an I/O call fails (typically the device was unplugged), so the
    // read loop can notice the link is gone instead of spinning on empty reads.
    @Volatile private var broken = false

    init {
        try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (error: Exception) {
            try { port.close() } catch (_: Exception) { }
            throw error
        }
    }

    override val kind: String get() = "usb"

    override val isOpen: Boolean
        get() = !broken && try { port.isOpen } catch (_: Exception) { false }

    @Synchronized
    override fun write(bytes: ByteArray) {
        try {
            port.write(bytes, WRITE_TIMEOUT_MS)
        } catch (_: Exception) {
            broken = true
        }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        try {
            port.read(buf, timeoutMs)
        } catch (_: Exception) {
            broken = true
            0
        }

    override fun close() {
        broken = true
        try { port.close() } catch (e: Exception) { app.brucia.BruciaLog.d("UsbTransport", "close failed: ${e.message}") }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
