package app.brucia

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.brucia.ui.bruciaColorScheme
import app.brucia.ui.rgb565ToColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast of the text roles screens draw straight on the app background or on cards.
 * BruciaTheme provides onBackground as the default content color; before that, a Text without a
 * color outside a Surface (e.g. the Sub-GHz "Listen" label) was LocalContentColor's default black.
 */
class ThemeContrastTest {

    private fun ratio(fg: Color, bg: Color): Double {
        val a = fg.luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b).toDouble() / minOf(a, b).toDouble()
    }

    private val accents = listOf(null, 0xF81F /* magenta */, 0x07E0 /* green */, 0x001F /* blue */, 0xFFFF /* white */)
        .map { it?.let(::rgb565ToColor) }

    @Test fun defaultContentColorOnBackgroundMeetsAa() {
        for (accent in accents) {
            val cs = bruciaColorScheme(accent)
            val r = ratio(cs.onBackground, cs.background)
            assertTrue("onBackground/background $r with accent $accent", r >= 4.5)
        }
    }

    @Test fun previousDefaultBlackFailedAa() {
        val cs = bruciaColorScheme()
        val r = ratio(Color.Black, cs.background)
        assertTrue("black/background $r", r < 1.5)
    }

    @Test fun secondaryTextRolesMeetAa() {
        for (accent in accents) {
            val cs = bruciaColorScheme(accent)
            for ((name, pair) in mapOf(
                "onSurface/surface" to (cs.onSurface to cs.surface),
                "onSurfaceVariant/background" to (cs.onSurfaceVariant to cs.background),
                "onSurfaceVariant/surface" to (cs.onSurfaceVariant to cs.surface),
                "onSurface/surfaceContainerHighest" to (cs.onSurface to cs.surfaceContainerHighest),
            )) {
                val r = ratio(pair.first, pair.second)
                assertTrue("$name $r with accent $accent", r >= 4.5)
            }
        }
    }

    @Test fun ratioMatchesTheWcagReferenceValues() {
        assertEquals(21.0, ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, ratio(Color.White, Color.White), 0.0001)
    }
}
