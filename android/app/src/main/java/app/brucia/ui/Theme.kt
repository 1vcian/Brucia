package app.brucia.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brucia "flame" palette — it has to burn. 🔥
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
fun BruciaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BruciaColors, content = content)
}
