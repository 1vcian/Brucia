package app.brucia.ble

/** A BLE device observed by the device's scan (computed/listed on the phone). */
data class BleDevice(
    val mac: String,
    val name: String,
    val rssi: Int,
    val timeMillis: Long,
)
