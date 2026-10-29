package app.brucia

import app.brucia.report.SessionExport
import app.brucia.wardrive.ApObservation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionExportTest {
    @Test fun jsonRoundTrip() {
        val aps = listOf(
            ApObservation("AA:BB", "Net", 6, -55, "wpa2", 45.0, 9.0, 0L, 3),
            ApObservation("CC:DD", "", 1, -80, "open", null, null, 0L, 1),
        )
        val json = SessionExport.build(aps, 4, "secret123")
        val o = JSONObject(json)
        assertEquals("Brucia", o.getString("tool"))
        assertEquals(4, o.getInt("bleDevices"))
        assertEquals("secret123", o.getString("crackResult"))
        val arr = o.getJSONArray("accessPoints")
        assertEquals(2, arr.length())
        assertEquals("AA:BB", arr.getJSONObject(0).getString("bssid"))
        assertEquals(3, arr.getJSONObject(0).getInt("seen"))
    }
}
