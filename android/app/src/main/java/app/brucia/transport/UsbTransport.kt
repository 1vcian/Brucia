package app.brucia.transport

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort

/** USB-CDC transport backed by usb-serial-for-android. */
class UsbTransport(
    connection: UsbDeviceConnection,
    private val port: UsbSerialPort,
    baudRate: Int = 115200,
) : Transport {

    init {
        port.open(connection)
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override val isOpen: Boolean get() = port.isOpen

    override fun write(bytes: ByteArray) {
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        try {
            port.read(buf, timeoutMs)
        } catch (_: Exception) {
            0
        }

    override fun close() {
        try { port.close() } catch (_: Exception) {}
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
