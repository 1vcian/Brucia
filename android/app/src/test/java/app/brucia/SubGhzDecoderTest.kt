package app.brucia

import app.brucia.subghz.OokProtocol
import app.brucia.subghz.OokProtocols
import app.brucia.subghz.SubGhzDecoder
import app.brucia.subghz.SubGhzEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubGhzDecoderTest {

    @Test fun decodesPrincetonKey() {
        val timings = SubGhzEncoder.encode("A5B3C7", 24, 350, 4)
        val out = SubGhzDecoder.decode(timings)
        assertTrue("should decode", out.isNotEmpty())
        val sig = out.first()
        assertEquals("Princeton", sig.protocol)
        assertEquals(24, sig.bits)
        assertEquals("A5B3C7", sig.codeHex)
        assertEquals("fixed", sig.classification)
        assertEquals(4, sig.repeats)
    }

    @Test fun decodesCameKey() {
        val came = OokProtocols.byCanonical("CAME")!!
        val timings = SubGhzEncoder.encode(came, "A5A", 12, 320, 4)
        val out = SubGhzDecoder.decode(timings)
        assertTrue("should decode", out.isNotEmpty())
        val sig = out.first()
        assertEquals("CAME", sig.protocol)
        assertEquals(12, sig.bits)
        assertEquals("A5A", sig.codeHex)
        assertEquals("fixed", sig.classification)
    }

    @Test fun decodesNiceFlo() {
        val nice = OokProtocols.byCanonical("NICE_FLO")!!
        val timings = SubGhzEncoder.encode(nice, "ABC", 12, 700, 3)
        val out = SubGhzDecoder.decode(timings)
        assertTrue(out.isNotEmpty())
        assertEquals("NICE_FLO", out.first().protocol)
        assertEquals("ABC", out.first().codeHex)
    }

    @Test fun classifiesKeeloqAsRolling() {
        // Synthetic 64-bit PWM (ratio 2) frame - the KeeLoq over-the-air shape.
        val pwm = OokProtocol("KeeLoq", "KeeLoq", 400, 1 to 2, 2 to 1, 0, false)
        val timings = SubGhzEncoder.encode(pwm, "123456789ABCDEF0", 64, 400, 3)
        val out = SubGhzDecoder.decode(timings)
        assertTrue("should decode something", out.isNotEmpty())
        val rolling = out.first()
        assertEquals("rolling", rolling.classification)
        assertEquals("KeeLoq", rolling.protocol)
        assertTrue("serial extracted", rolling.serialHex.isNotEmpty())
        assertTrue("has a replay warning", rolling.note.contains("replay", ignoreCase = true))
    }

    @Test fun emptyOnNoise() {
        assertTrue(SubGhzDecoder.decode(intArrayOf(10, 20, 30)).isEmpty())
    }
}
