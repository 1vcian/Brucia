package app.brucia

import app.brucia.audio.Rtttl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RTTTL parser robustness. A malformed tempo/duration header must not produce absurd note
 * durations (Int.MAX_VALUE ms from a divide-by-zero, or negative ms).
 */
class RtttlTest {

    private fun durations(rtttl: String) = Rtttl.parse(rtttl).map { it.ms }

    @Test fun validSongParses() {
        val notes = Rtttl.parse("Tetris:d=4,o=5,b=160:e6,8b,c6")
        assertEquals(3, notes.size)
        assertTrue("durations must be positive", notes.all { it.ms > 0 })
    }

    @Test fun zeroTempoDoesNotOverflow() {
        val ms = durations("Bad:d=4,o=5,b=0:c,d,e")
        assertTrue("b=0 must not yield huge/overflowed durations", ms.isNotEmpty() && ms.all { it in 1..10_000 })
    }

    @Test fun zeroDefaultDurationDoesNotOverflow() {
        val ms = durations("Bad:d=0,o=5,b=120:c,d,e")
        assertTrue("d=0 must not yield huge durations", ms.all { it in 1..10_000 })
    }

    @Test fun negativeTempoFallsBack() {
        val ms = durations("Bad:d=4,o=5,b=-5:c")
        assertTrue("negative tempo must not yield negative durations", ms.all { it > 0 })
    }

    @Test fun composerBuildIsValidRtttl() {
        val rtttl = Rtttl.build("My Song", 120, listOf("8c6", "4a#5", "8p"))
        val notes = Rtttl.parse(rtttl)
        assertEquals(3, notes.size)
        assertTrue(notes.all { it.ms > 0 })
    }
}
