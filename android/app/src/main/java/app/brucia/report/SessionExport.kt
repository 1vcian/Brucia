package app.brucia.report

import app.brucia.wardrive.ApObservation
import org.json.JSONArray
import org.json.JSONObject

/** Machine-readable JSON export of a session (for archival / interop). */
object SessionExport {
    fun build(aps: List<ApObservation>, bleCount: Int, crackResult: String?): String {
        val root = JSONObject()
        root.put("tool", "Brucia")
        root.put("generatedAt", System.currentTimeMillis())
        root.put("bleDevices", bleCount)
        root.put("crackResult", crackResult ?: JSONObject.NULL)
        val arr = JSONArray()
        for (ap in aps) {
            arr.put(
                JSONObject()
                    .put("bssid", ap.bssid).put("ssid", ap.ssid).put("channel", ap.channel)
                    .put("rssi", ap.rssi).put("enc", ap.enc)
                    .put("lat", ap.lat ?: JSONObject.NULL).put("lon", ap.lon ?: JSONObject.NULL)
                    .put("seen", ap.seen)
            )
        }
        root.put("accessPoints", arr)
        return root.toString(2)
    }
}
