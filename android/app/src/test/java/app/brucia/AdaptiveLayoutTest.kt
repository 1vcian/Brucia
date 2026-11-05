package app.brucia

import app.brucia.ui.AdaptiveLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutTest {

    // Grid width = window (or capped dashboard) width minus the 2 x 16dp side padding.
    private fun columnsForWindow(windowDp: Float): Int {
        val content = minOf(windowDp, AdaptiveLayout.HOME_MAX_WIDTH_DP) - 32f
        return AdaptiveLayout.homeGridColumns(content)
    }

    @Test
    fun phonesInPortraitKeepTwoColumns() {
        for (w in listOf(320f, 360f, 393f, 411f, 430f, 480f)) {
            assertEquals("window $w dp", 2, columnsForWindow(w))
        }
    }

    @Test
    fun widerWindowsGetMoreColumns() {
        assertEquals(3, columnsForWindow(600f))   // small tablet / unfolded foldable
        assertEquals(3, columnsForWindow(673f))   // Pixel Fold inner display, portrait
        assertEquals(4, columnsForWindow(800f))   // 1920x1200 at 240 dpi, portrait
        assertEquals(4, columnsForWindow(851f))   // Pixel 5 in landscape
        assertEquals(5, columnsForWindow(1280f))  // 1920x1200 at 240 dpi, landscape (capped at 1040)
    }

    @Test
    fun columnBounds() {
        assertEquals(2, AdaptiveLayout.homeGridColumns(0f))
        assertEquals(2, AdaptiveLayout.homeGridColumns(-5f))
        assertEquals(2, AdaptiveLayout.homeGridColumns(Float.NaN))
        assertEquals(2, AdaptiveLayout.homeGridColumns(503f))
        assertEquals(3, AdaptiveLayout.homeGridColumns(504f)) // 3 x 160 + 2 x 12
        assertEquals(AdaptiveLayout.HOME_MAX_COLUMNS, AdaptiveLayout.homeGridColumns(5000f))
    }

    @Test
    fun toolCapIsMaterialExpandedBreakpoint() {
        assertEquals(840f, AdaptiveLayout.TOOL_MAX_WIDTH_DP)
        assertEquals(840f, AdaptiveLayout.ToolMaxWidth.value)
        assertEquals(1040f, AdaptiveLayout.HomeMaxWidth.value)
    }
}
