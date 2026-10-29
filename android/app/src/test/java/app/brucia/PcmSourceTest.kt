package app.brucia

import app.brucia.audio.PcmSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSourceTest {

    @Test fun toneHasExpectedLength() {
        val s = PcmSource.tone(440.0, 0.5)
        assertEquals(PcmSource.SAMPLE_RATE / 2, s.size)
    }

    @Test fun resampleHalvesRateLength() {
        // 32 kHz mono -> 16 kHz should roughly halve the sample count.
        val src = ShortArray(32000) { (it % 100).toShort() }
        val out = PcmSource.downmixAndResample(src, 32000, 1)
        assertEquals(16000, out.size)
    }

    @Test fun downmixStereoToMono() {
        // Two interleaved frames: (10,20) and (30,40) -> averages 15, 35 at same rate.
        val src = shortArrayOf(10, 20, 30, 40)
        val out = PcmSource.downmixAndResample(src, PcmSource.SAMPLE_RATE, 2)
        assertEquals(2, out.size)
        assertEquals(15, out[0].toInt())
        assertEquals(35, out[1].toInt())
    }

    @Test fun littleEndianPacking() {
        val bytes = PcmSource.shortsToLittleEndian(shortArrayOf(0x0102, 0x7F00), 2)
        assertEquals(4, bytes.size)
        assertEquals(0x02, bytes[0].toInt() and 0xFF)
        assertEquals(0x01, bytes[1].toInt() and 0xFF)
        assertEquals(0x00, bytes[2].toInt() and 0xFF)
        assertEquals(0x7F, bytes[3].toInt() and 0xFF)
    }

    @Test fun rmsLevelInRange() {
        val loud = PcmSource.rmsLevel(ShortArray(100) { Short.MAX_VALUE })
        val silent = PcmSource.rmsLevel(ShortArray(100))
        assertTrue(loud > 0.9f)
        assertEquals(0f, silent, 0.0001f)
    }

    @Test fun emptyInputsAreSafe() {
        assertEquals(0, PcmSource.downmixAndResample(ShortArray(0), 44100, 2).size)
        assertEquals(0f, PcmSource.rmsLevel(ShortArray(0)), 0f)
    }
}
