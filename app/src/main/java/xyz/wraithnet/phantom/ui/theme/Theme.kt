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
    primary = Color(0xFFB9C1CC),
    onPrimary = Color(0xFF17191D),
    primaryContainer = Color(0xFF343A42),
    onPrimaryContainer = Color(0xFFE6EAF0),
    secondary = Color(0xFF8E99A7),
    onSecondary = Color(0xFF17191D),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF181B1F),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF252A2F),
    onSurfaceVariant = Color(0xFFADB5BF),
    outline = Color(0xFF4A515A),
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
