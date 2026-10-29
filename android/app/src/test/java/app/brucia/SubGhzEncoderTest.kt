package app.brucia

import app.brucia.subghz.SubGhzDecoder
import app.brucia.subghz.SubGhzEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubGhzEncoderTest {
    @Test fun encodeThenDecodeRoundTrip() {
        val timings = SubGhzEncoder.encode("A5B3C7", 24, 350, 3)
        val out = SubGhzDecoder.decode(timings)
        assertTrue("should decode", out.isNotEmpty())
        assertEquals(24, out[0].bits)
        assertEquals("A5B3C7", out[0].codeHex)
    }
}
