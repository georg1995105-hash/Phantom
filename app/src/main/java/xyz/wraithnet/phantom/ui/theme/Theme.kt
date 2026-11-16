package xyz.wraithnet.phantom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class PhantomThemeMode(val title: String) {
    AUTO("Авто"),
    LIGHT("Светлая"),
    GRAPHITE("Графит"),
    NIGHT("Ночная"),
}

private val GraphiteColors = darkColorScheme(
    primary = Color(0xFF9CB9ED),
    onPrimary = Color(0xFF102034),
    primaryContainer = Color(0xFF24344B),
    onPrimaryContainer = Color(0xFFF1F5FF),
    secondary = Color(0xFFA0B4D2),
    onSecondary = Color(0xFF10151F),
    background = Color(0xFF10151D),
    onBackground = Color(0xFFEFF3FA),
    surface = Color(0xFF1A222E),
    onSurface = Color(0xFFEFF3FA),
    surfaceVariant = Color(0xFF273241),
    onSurfaceVariant = Color(0xFFA9B6CB),
    outline = Color(0xFF435267),
    error = Color(0xFFFFADB0),
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

private val LightColors = lightColorScheme(
    primary = Color(0xFF355D98),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EDFA),
    onPrimaryContainer = Color(0xFF203955),
    secondary = Color(0xFF5E7089),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF182232),
    surface = Color.White,
    onSurface = Color(0xFF182232),
    surfaceVariant = Color(0xFFEBF0F6),
    onSurfaceVariant = Color(0xFF607187),
    outline = Color(0xFFD0D8E3),
    error = Color(0xFFB3261E),
)

@Composable
fun PhantomTheme(
    mode: PhantomThemeMode = PhantomThemeMode.AUTO,
    content: @Composable () -> Unit,
) {
    val colors = when (mode) {
        PhantomThemeMode.AUTO -> if (isSystemInDarkTheme()) GraphiteColors else LightColors
        PhantomThemeMode.LIGHT -> LightColors
        PhantomThemeMode.GRAPHITE -> GraphiteColors
        PhantomThemeMode.NIGHT -> NightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
