package app.brucia.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

// Brucia "flame" palette - the UI has to burn.
val Ember = Color(0xFFFF6D1A)
val EmberDeep = Color(0xFFE23E00)
val Amber = Color(0xFFFFB300)
private val Bg = Color(0xFF0E0E10)
private val Surf = Color(0xFF1A1A1D)
private val SurfHi = Color(0xFF242428)
private val OnDark = Color(0xFFF3F1EF)
private val Muted = Color(0xFFB8B2AC)

private val BruciaColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF1A0800),
    secondary = Amber,
    onSecondary = Color(0xFF1A1200),
    tertiary = EmberDeep,
    background = Bg,
    onBackground = OnDark,
    surface = Surf,
    onSurface = OnDark,
    surfaceVariant = SurfHi,
    onSurfaceVariant = Muted,
    error = Color(0xFFFF5449),
    outline = Color(0xFF3A3A3F),
    // Material3 1.4 draws Card and the unchecked Switch track with surfaceContainerHighest
    // (1.2 used surfaceVariant): keep them on the same Brucia grey.
    surfaceContainerHighest = SurfHi,
)

/**
 * Roles that Material3 1.4 components read where 1.2 used other ones:
 * - surfaceContainerHigh (AlertDialog) and surfaceContainer (DropdownMenu) replace the
 *   surface tinted by surfaceTint at 6 dp / 3 dp tonal elevation that 1.2 drew; same
 *   colors as before (surfaceTint stays Ember, also with a device accent, as before).
 * - secondaryContainer is the track of sliders and progress indicators: a dark blend of
 *   the surface and primary (Ember or the device accent), visible on the background
 *   and on cards.
 */
private fun ColorScheme.withBruciaContainers(): ColorScheme = copy(
    surfaceContainerHigh = surfaceColorAtElevation(6.dp),
    surfaceContainer = surfaceColorAtElevation(3.dp),
    secondaryContainer = lerp(Surf, primary, 0.3f),
    onSecondaryContainer = OnDark,
)

@Composable
fun BruciaTheme(accent: Color? = null, content: @Composable () -> Unit) {
    // When connected, the device's UI theme wins: its primary color becomes the app accent so the
    // whole UI (buttons, titles, the flame) matches the device (e.g. a pink device -> a pink app).
    val scheme = remember(accent) {
        val base = if (accent == null) BruciaColors else BruciaColors.copy(
            primary = accent,
            secondary = accent,
            tertiary = accent,
        )
        base.withBruciaContainers()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Convert an RGB565 color (as the device reports priColor) to a Compose Color, or null if 0. */
fun rgb565ToColor(c: Int): Color? {
    if (c == 0) return null
    val r5 = (c shr 11) and 0x1F
    val g6 = (c shr 5) and 0x3F
    val b5 = c and 0x1F
    val r = (r5 shl 3) or (r5 shr 2)
    val g = (g6 shl 2) or (g6 shr 4)
    val b = (b5 shl 3) or (b5 shr 2)
    return Color(r, g, b)
}
