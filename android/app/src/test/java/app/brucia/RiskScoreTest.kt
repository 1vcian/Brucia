package app.brucia

import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.RiskScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskScoreTest {
    private fun ap(enc: String, ssid: String = "x") = ApObservation("M", ssid, 6, -50, enc, null, null, 0L)

    @Test fun openIsHigh() { assertEquals("high", RiskScore.of(ap("open")).level) }
    @Test fun wpa3IsLow() { assertEquals("low", RiskScore.of(ap("wpa2/wpa3")).level) }
    @Test fun wpa2IsLowOrMedium() { assertTrue(RiskScore.of(ap("wpa2")).score in 20..45) }
    @Test fun defaultSsidRaisesScore() {
        assertTrue(RiskScore.of(ap("wpa2", "TIM-12345678")).score > RiskScore.of(ap("wpa2", "MyHome")).score)
    }
}
