package app.brucia.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.brucia.wardrive.ApObservation

@Entity(tableName = "ap")
data class ApEntity(
    @PrimaryKey val bssid: String,
    val ssid: String,
    val channel: Int,
    val rssi: Int,
    val enc: String,
    val lat: Double?,
    val lon: Double?,
    val timeMillis: Long,
    // RSSI-weighted accumulators for position estimation (offload computation).
    val wLat: Double = 0.0,
    val wLon: Double = 0.0,
    val wSum: Double = 0.0,
    val seen: Int = 1,
) {
    fun toObservation() = ApObservation(bssid, ssid, channel, rssi, enc, lat, lon, timeMillis, seen)

    companion object {
        fun from(o: ApObservation) =
            ApEntity(o.bssid, o.ssid, o.channel, o.rssi, o.enc, o.lat, o.lon, o.timeMillis)
    }
}
