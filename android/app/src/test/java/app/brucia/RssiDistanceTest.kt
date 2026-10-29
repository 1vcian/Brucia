package app.brucia

import app.brucia.wardrive.RssiDistance
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiDistanceTest {
    @Test fun atReferenceIsAboutOneMeter() {
        val d = RssiDistance.meters(-40) // equals txPowerAt1m
        assertTrue(d in 0.9..1.1)
    }

    @Test fun weakerSignalIsFarther() {
        assertTrue(RssiDistance.meters(-80) > RssiDistance.meters(-50))
    }
}
