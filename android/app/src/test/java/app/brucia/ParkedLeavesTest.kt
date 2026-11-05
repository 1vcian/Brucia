package app.brucia

import app.brucia.ui.ParkedLeaves
import org.junit.Assert.assertEquals
import org.junit.Test

/** Store behind LeaveEffect: a rotation parks the leave, the screen coming back claims it. */
class ParkedLeavesTest {

    @Test fun leaveOutsideAConfigurationChangeRunsAtOnce() {
        val leaves = ParkedLeaves()
        var runs = 0
        leaves.leave("a", changingConfigurations = false) { runs++ }
        assertEquals(1, runs)
        assertEquals(0, leaves.size)
    }

    @Test fun screenComingBackClaimsItsParkedLeave() {
        val leaves = ParkedLeaves()
        var runs = 0
        leaves.enter("a")
        repeat(2) {
            leaves.leave("a", changingConfigurations = true) { runs++ }
            leaves.enter("a")
            leaves.settle()
        }
        assertEquals(0, runs)
        leaves.leave("a", changingConfigurations = false) { runs++ }
        assertEquals(1, runs)
    }

    @Test fun screenNotComingBackIsLeftWhenTheNewUiSettles() {
        val leaves = ParkedLeaves()
        val ran = mutableListOf<String>()
        leaves.leave("a", changingConfigurations = true) { ran += "a" }
        leaves.leave("b", changingConfigurations = true) { ran += "b" }
        leaves.enter("b")
        assertEquals(emptyList<String>(), ran)
        leaves.settle()
        assertEquals(listOf("a"), ran)
        leaves.settle()
        assertEquals(listOf("a"), ran)
        assertEquals(0, leaves.size)
    }
}
