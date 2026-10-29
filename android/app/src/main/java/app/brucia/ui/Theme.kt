package app.brucia.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
)

@Composable
fun BruciaTheme(accent: Color? = null, content: @Composable () -> Unit) {
    // When connected, the device's UI theme wins: its primary color becomes the app accent so the
    // whole UI (buttons, titles, the flame) matches the device (e.g. a pink device -> a pink app).
    val scheme = if (accent == null) BruciaColors else BruciaColors.copy(
        primary = accent,
        secondary = accent,
        tertiary = accent,
    )
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
