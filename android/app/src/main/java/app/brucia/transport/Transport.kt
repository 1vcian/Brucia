package app.brucia.transport

/** A raw byte pipe to the device. Implementations: USB-CDC (now), Wi-Fi, BLE. */
interface Transport {
    val isOpen: Boolean
    /** Transport family, e.g. "usb" / "wifi" / "ble"; lets the UI gate mode-specific features. */
    val kind: String get() = "unknown"
    fun write(bytes: ByteArray)
    /** Read up to [buf].size bytes; returns the count (0 on timeout). */
    fun read(buf: ByteArray, timeoutMs: Int): Int
    fun close()
}
