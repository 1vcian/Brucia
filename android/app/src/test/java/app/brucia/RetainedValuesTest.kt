package app.brucia

import app.brucia.ui.RetainedValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Store behind rememberRetained: kept across a configuration change, dropped when the screen is left. */
class RetainedValuesTest {

    @Test fun valueSurvivesAConfigurationChange() {
        val store = RetainedValues()
        val first = store.getOrPut("a") { mutableListOf("host") }
        store.release("a", changingConfigurations = true)
        val again = store.getOrPut("a") { mutableListOf<String>() }
        assertSame(first, again)
        assertEquals(listOf("host"), again)
    }

    @Test fun valueIsDroppedWhenTheScreenIsLeft() {
        val store = RetainedValues()
        val first = store.getOrPut("a") { Any() }
        store.release("a", changingConfigurations = false)
        assertEquals(0, store.size)
        assertNotSame(first, store.getOrPut("a") { Any() })
    }

    @Test fun idsAreIndependent() {
        val store = RetainedValues()
        val a = store.getOrPut("a") { Any() }
        val b = store.getOrPut("b") { Any() }
        assertNotSame(a, b)
        store.release("a", changingConfigurations = false)
        assertSame(b, store.getOrPut("b") { Any() })
        assertEquals(1, store.size)
    }

    @Test fun initRunsOncePerId() {
        val store = RetainedValues()
        var inits = 0
        repeat(3) { store.getOrPut("a") { inits++ } }
        assertEquals(1, inits)
    }
}
