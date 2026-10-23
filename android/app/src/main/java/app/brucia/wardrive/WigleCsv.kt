package app.brucia.wardrive

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export observations in the WiGLE CSV format (importable by common tools). */
object WigleCsv {

    fun build(aps: Collection<ApObservation>): String {
        val sb = StringBuilder()
        sb.append(
            "WigleWifi-1.4,appRelease=0.1.0,model=Brucia,release=0.1.0," +
                "device=brucia,display=,board=,brand=brucia\n"
        )
        sb.append(
            "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude," +
                "CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n"
        )
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (ap in aps) {
            sb.append(ap.bssid).append(',')
                .append(csv(ap.ssid)).append(',')
                .append(ap.enc).append(',')
                .append(fmt.format(Date(ap.timeMillis))).append(',')
                .append(ap.channel).append(',')
                .append(ap.rssi).append(',')
                .append(ap.lat ?: "").append(',')
                .append(ap.lon ?: "").append(',')
                .append("0,0,WIFI\n")
        }
        return sb.toString()
    }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
