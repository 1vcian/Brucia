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

    @Test fun offeredOrdersAreWithinTheGeneratorRange() {
        // Every preset the UI shows must be a valid generator order (no 23/24 that throw).
        assertEquals(true, DeBruijn.OFFERED_ORDERS.all { it in 1..DeBruijn.MAX_ORDER })
        assertEquals(true, DeBruijn.OFFERED_ORDERS.isNotEmpty())
    }

    @Test fun transmitIsRefusedForEveryOfferedOrder() {
        // The device subghz tx path cannot carry a sweep, so every offered order is refused with a
        // clear reason that names the sweep size and says nothing was sent (no false "sent").
        for (n in DeBruijn.OFFERED_ORDERS) {
            val reason = DeBruijn.transmitRejection(n)
            val bits = 1L shl n
            assertEquals("names the sweep size for $n", true, reason.contains("$bits bits"))
            assertEquals("says it cannot carry it for $n", true, reason.contains("cannot carry"))
            assertEquals("states nothing was transmitted for $n", true, reason.contains("Nothing was transmitted"))
        }
    }

    @Test fun transmitRejectsOutOfRangeOrders() {
        for (n in listOf(-1, 0, DeBruijn.MAX_ORDER + 1, 23, 24, 100)) {
            val reason = DeBruijn.transmitRejection(n)
            assertEquals("out-of-range reason for $n", true, reason.contains("out of range"))
            assertEquals("bounds stated for $n", true, reason.contains("1..${DeBruijn.MAX_ORDER}"))
            assertEquals("nothing transmitted for $n", true, reason.contains("Nothing was transmitted"))
        }
    }

    @Test fun deviceKeyCannotHoldOfferedSweeps() {
        // Documents the real limit the UI reflects: every offered order's sweep exceeds a single code.
        for (n in DeBruijn.OFFERED_ORDERS) {
            assertEquals("order $n sweep exceeds the device key", true, (1L shl n) > DeBruijn.DEVICE_TX_KEY_BITS)
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
