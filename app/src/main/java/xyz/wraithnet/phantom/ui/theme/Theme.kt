package xyz.wraithnet.phantom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class PhantomThemeMode(val title: String) {
    GRAPHITE("Графит"),
    NIGHT("Ночная"),
}

private val GraphiteColors = darkColorScheme(
    primary = Color(0xFFAFC4EA),
    onPrimary = Color(0xFF10151F),
    primaryContainer = Color(0xFF25334A),
    onPrimaryContainer = Color(0xFFEAF2FF),
    secondary = Color(0xFF97ACC7),
    onSecondary = Color(0xFF10151F),
    background = Color(0xFF0D1119),
    onBackground = Color(0xFFE8EDF6),
    surface = Color(0xFF191E28),
    onSurface = Color(0xFFE8EDF6),
    surfaceVariant = Color(0xFF232A36),
    onSurfaceVariant = Color(0xFF9EAABF),
    outline = Color(0xFF414D60),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFF8DA2FF),
    onPrimary = Color(0xFF111522),
    primaryContainer = Color(0xFF29355F),
    onPrimaryContainer = Color(0xFFE1E6FF),
    secondary = Color(0xFF9FAACD),
    onSecondary = Color(0xFF171A25),
    background = Color(0xFF0B0E17),
    onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF151B2A),
    onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF252C42),
    onSurfaceVariant = Color(0xFFB0B8CC),
    outline = Color(0xFF3A4466),
)

@Composable
fun PhantomTheme(
    mode: PhantomThemeMode = PhantomThemeMode.GRAPHITE,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when (mode) {
            PhantomThemeMode.GRAPHITE -> GraphiteColors
            PhantomThemeMode.NIGHT -> NightColors
        },
        content = content,
    )
}
