package app.brucia.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width rules for wide windows (tablets, foldables, desktop windows, phones in landscape).
 *
 * Phones in portrait are never affected: every rule below resolves to the phone layout for a
 * window narrower than [TOOL_MAX_WIDTH_DP] (the home grid stays at 2 columns below 504dp of
 * content width, which is wider than any phone in portrait).
 */
object AdaptiveLayout {
    /** Smallest home-grid tile width; more columns are added only when each keeps this much. */
    const val HOME_MIN_CELL_DP = 160f
    /** Gap between home-grid tiles (matches the phone layout's 12dp spacing). */
    const val HOME_GAP_DP = 12f
    /** The phone layout's column count, also the minimum on any window. */
    const val HOME_MIN_COLUMNS = 2
    /** Upper bound so a very wide window does not turn the grid into a single strip. */
    const val HOME_MAX_COLUMNS = 6

    /** Maximum width of the home dashboard (grid + connect card + search), centered. */
    const val HOME_MAX_WIDTH_DP = 1040f
    /**
     * Maximum width of a tool screen and of the category tool list, centered. 840dp is the
     * Material "expanded" window-width breakpoint: below it the screen keeps the full width.
     */
    const val TOOL_MAX_WIDTH_DP = 840f

    val HomeMaxWidth: Dp = HOME_MAX_WIDTH_DP.dp
    val ToolMaxWidth: Dp = TOOL_MAX_WIDTH_DP.dp

    /**
     * Columns for the home category grid given the grid's own width in dp (after padding):
     * as many [HOME_MIN_CELL_DP] tiles as fit with [HOME_GAP_DP] gaps, never fewer than the phone's
     * 2 and never more than [HOME_MAX_COLUMNS].
     */
    fun homeGridColumns(gridWidthDp: Float): Int {
        if (gridWidthDp.isNaN() || gridWidthDp <= 0f) return HOME_MIN_COLUMNS
        val fit = ((gridWidthDp + HOME_GAP_DP) / (HOME_MIN_CELL_DP + HOME_GAP_DP)).toInt()
        return fit.coerceIn(HOME_MIN_COLUMNS, HOME_MAX_COLUMNS)
    }
}

/**
 * Centers [content] horizontally and caps its width at [maxWidth]; the full height is kept so
 * screens that fill the size or scroll vertically behave as before. On a window narrower than
 * [maxWidth] the content gets exactly the same constraints as without this wrapper.
 */
@Composable
fun CenteredMaxWidth(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize(), content = content)
    }
}
