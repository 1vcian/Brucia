package app.brucia.transport

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/** USB-CDC transport backed by usb-serial-for-android. */
class UsbTransport internal constructor(private val port: UsbSerialPort) : Transport {

    // Set when an I/O call fails (typically the device was unplugged), so the
    // read loop can notice the link is gone instead of spinning on empty reads.
    @Volatile private var broken = false

    /**
     * Opens [port] on [connection]. On failure both are released before the error propagates (see
     * [releaseUsbPort]): the port is closed only if this open succeeded, so a refused open never
     * closes a port another transport still uses; otherwise the connection is closed directly.
     */
    constructor(connection: UsbDeviceConnection, port: UsbSerialPort, baudRate: Int = 115200) : this(port) {
        var opened = false
        try {
            port.open(connection)
            opened = true
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (error: Exception) {
            releaseUsbPort(
                opened,
                closePort = { try { port.close() } catch (e: Exception) { app.brucia.BruciaLog.d("UsbTransport", "close port: ${e.message}") } },
                closeConnection = { try { connection.close() } catch (e: Exception) { app.brucia.BruciaLog.d("UsbTransport", "close connection: ${e.message}") } },
            )
            throw error
        }
    }

    override val kind: String get() = "usb"

    override val isOpen: Boolean
        get() = !broken && try { port.isOpen } catch (_: Exception) { false }

    @Synchronized
    override fun write(bytes: ByteArray) {
        if (!isOpen) throw IOException("USB connection is closed")
        try {
            port.write(bytes, WRITE_TIMEOUT_MS)
        } catch (error: Exception) {
            broken = true
            throw IOException("USB write failed: ${error.message ?: "device disconnected"}", error)
        }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        try {
            port.read(buf, timeoutMs)
        } catch (_: Exception) {
            broken = true
            0
        }

    /** Closing the port also closes its UsbDeviceConnection (usb-serial-for-android 3.8.1, see [releaseUsbPort]). */
    override fun close() {
        broken = true
        try { port.close() } catch (e: Exception) { app.brucia.BruciaLog.d("UsbTransport", "close failed: ${e.message}") }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
