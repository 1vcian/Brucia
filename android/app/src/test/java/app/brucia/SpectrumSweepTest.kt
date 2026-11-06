package app.brucia

import app.brucia.wardrive.SpectrumSweep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * subghz.spectrum per-sweep events carry `start`/`step` in kHz (proto/PROTOCOL.md, firmware
 * subghzSpecService); SpectrumSweep.freqs are Hz (the screens divide by 1e6 for MHz labels).
 */
class SpectrumSweepTest {
    @Test fun sweepEventKilohertzBecomeHertz() {
        val data = JSONObject().put("start", 300_000).put("step", 1_000).put("count", 3)
            .put("rssi", JSONArray(listOf(-90, -70, -100)))
        val sweep = SpectrumSweep.fromSweepEvent(data, 42L)!!
        assertArrayEquals(longArrayOf(300_000_000L, 301_000_000L, 302_000_000L), sweep.freqs)
        assertArrayEquals(intArrayOf(-90, -70, -100), sweep.rssi)
        assertEquals(42L, sweep.time)
        // The default 300-348 MHz window labels as 300.00-348.00 MHz, not 0.30-0.35.
        val full = JSONObject().put("start", 300_000).put("step", 1_000)
            .put("rssi", JSONArray(List(49) { -90 }))
        val f = SpectrumSweep.fromSweepEvent(full, 0L)!!
        assertEquals(300.0, f.freqs.first() / 1_000_000.0, 1e-9)
        assertEquals(348.0, f.freqs.last() / 1_000_000.0, 1e-9)
    }

    @Test fun nonWholeMegahertzGridKeepsKilohertzPrecision() {
        val data = JSONObject().put("start", 433_920).put("step", 25).put("rssi", JSONArray(listOf(-80, -81)))
        assertArrayEquals(longArrayOf(433_920_000L, 433_945_000L), SpectrumSweep.fromSweepEvent(data, 0L)!!.freqs)
    }

    @Test fun perPointShapeIsNotASweep() {
        assertNull(SpectrumSweep.fromSweepEvent(JSONObject().put("freq", 433_920_000).put("rssi", -70), 0L))
    }
}
