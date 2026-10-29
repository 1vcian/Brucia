package app.brucia.wardrive

/** One access-point observation; GPS/time are added by the phone. */
data class ApObservation(
    val bssid: String,
    val ssid: String,
    val channel: Int,
    val rssi: Int,
    val enc: String,
    val lat: Double?,
    val lon: Double?,
    val timeMillis: Long,
    val seen: Int = 1,
)
