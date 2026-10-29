package app.brucia

import app.brucia.subghz.DeBruijn
import org.junit.Assert.assertEquals
import org.junit.Test

class DeBruijnTest {
    @Test fun coversAllWindowsOnce() {
        for (n in 1..8) {
            val s = DeBruijn.sequence(n)
            assertEquals(1 shl n, s.length)
            val seen = HashSet<String>()
            for (i in 0 until s.length) {
                val w = StringBuilder()
                for (j in 0 until n) w.append(s[(i + j) % s.length]) // cyclic window
                seen.add(w.toString())
            }
            assertEquals("all $n-bit codes covered once", 1 shl n, seen.size)
        }
    }

    @Test fun hexKeyEncodesTheWholeSequence() {
        for (n in 4..12) {
            val (hex, bits) = DeBruijn.hexKey(n)
            assertEquals("bit length is 2^n", 1 shl n, bits)
            assertEquals("hex is valid", true, hex.all { it in "0123456789ABCDEF" })
            // hex nibbles must hold at least all the bits (padded up to a nibble)
            assertEquals("nibble count covers the bits", (bits + 3) / 4, hex.length)
        }
    }
}
