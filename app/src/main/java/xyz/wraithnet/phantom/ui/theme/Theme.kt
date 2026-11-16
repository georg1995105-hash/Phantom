package xyz.wraithnet.phantom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class PhantomThemeMode(val title: String) {
    AUTO("Авто"),
    LIGHT("Светлая"),
    GRAPHITE("Графит"),
    NIGHT("Ночная"),
    FOREST("Тёмно-зелёная"),
}

// The AUTO palette intentionally differs from GRAPHITE and NIGHT on dark devices.
// Theme preference names for all existing installations must remain unchanged.
private val SystemDarkColors = darkColorScheme(
    primary = Color(0xFF9DBED7),
    onPrimary = Color(0xFF162D3D),
    primaryContainer = Color(0xFF263D4D),
    onPrimaryContainer = Color(0xFFE8F3F9),
    secondary = Color(0xFF9CB6BB),
    onSecondary = Color(0xFF142C30),
    background = Color(0xFF101820),
    onBackground = Color(0xFFE7EFF4),
    surface = Color(0xFF1C2831),
    onSurface = Color(0xFFE7EFF4),
    surfaceVariant = Color(0xFF2C3D49),
    onSurfaceVariant = Color(0xFFAEBDC9),
    outline = Color(0xFF50616F),
    error = Color(0xFFFFAFAF)
)

private val GraphiteColors = darkColorScheme(
    primary = Color(0xFFA2B9D5),
    onPrimary = Color(0xFF16283B),
    primaryContainer = Color(0xFF283647),
    onPrimaryContainer = Color(0xFFF1F5FB),
    secondary = Color(0xFFB7BFCD),
    onSecondary = Color(0xFF222A37),
    background = Color(0xFF15181E),
    onBackground = Color(0xFFF0F1F4),
    surface = Color(0xFF242934),
    onSurface = Color(0xFFF0F1F4),
    surfaceVariant = Color(0xFF313947),
    onSurfaceVariant = Color(0xFFBDC5D1),
    outline = Color(0xFF596575),
    error = Color(0xFFFFB1B1)
)

private val NightColors = darkColorScheme(
    primary = Color(0xFFB0A5EA),
    onPrimary = Color(0xFF211C40),
    primaryContainer = Color(0xFF312C53),
    onPrimaryContainer = Color(0xFFEDEAFF),
    secondary = Color(0xFFABA4CD),
    onSecondary = Color(0xFF292440),
    background = Color(0xFF0C0E18),
    onBackground = Color(0xFFEDECF5),
    surface = Color(0xFF1A1D2C),
    onSurface = Color(0xFFEDECF5),
    surfaceVariant = Color(0xFF292C41),
    onSurfaceVariant = Color(0xFFAFB3C9),
    outline = Color(0xFF515672)
)

private val ForestColors = darkColorScheme(
    primary = Color(0xFF82D8AF),
    onPrimary = Color(0xFF0F3123),
    primaryContainer = Color(0xFF264639),
    onPrimaryContainer = Color(0xFFE0F6E9),
    secondary = Color(0xFF9CC4AC),
    onSecondary = Color(0xFF193A2A),
    background = Color(0xFF0F1B18),
    onBackground = Color(0xFFE2F0E9),
    surface = Color(0xFF1B2C26),
    onSurface = Color(0xFFE2F0E9),
    surfaceVariant = Color(0xFF29443A),
    onSurfaceVariant = Color(0xFFABCABD),
    outline = Color(0xFF436B5B),
    error = Color(0xFFFFB6AD)
)

// Warm gray, rather than pure white; no glaring white subscription sheets.
private val LightColors = lightColorScheme(
    primary = Color(0xFF486C94),
    onPrimary = Color(0xFFF5F7FA),
    primaryContainer = Color(0xFFDCE5ED),
    onPrimaryContainer = Color(0xFF203649),
    secondary = Color(0xFF647D8F),
    onSecondary = Color(0xFFF5F7FA),
    background = Color(0xFFE5E9EC),
    onBackground = Color(0xFF263442),
    surface = Color(0xFFEDF0F2),
    onSurface = Color(0xFF263442),
    surfaceVariant = Color(0xFFDCE3E8),
    onSurfaceVariant = Color(0xFF596B79),
    outline = Color(0xFFB7C4CF),
    error = Color(0xFFAD4C43)
)

fun themeScheme(mode: PhantomThemeMode, systemDark: Boolean): ColorScheme {
    val base = when (mode) {
        PhantomThemeMode.AUTO -> if (systemDark) SystemDarkColors else LightColors
        PhantomThemeMode.LIGHT -> LightColors
        PhantomThemeMode.GRAPHITE -> GraphiteColors
        PhantomThemeMode.NIGHT -> NightColors
        PhantomThemeMode.FOREST -> ForestColors
    }
    // Material 3 dialogs and menus use surfaceContainerHigh by default.
    // Override all container tokens so they cannot fall back to purple
    // default M3 palette, especially in Forest, Auto and Graphite themes.
    return base.copy(
        surfaceDim = base.background,
        surfaceBright = base.surfaceVariant,
        surfaceContainerLowest = base.background,
        surfaceContainerLow = base.surface,
        surfaceContainer = base.surface,
        surfaceContainerHigh = base.surface,
        surfaceContainerHighest = base.surfaceVariant,
        surfaceTint = base.primary
    )
}

@Composable
fun PhantomTheme(
    mode: PhantomThemeMode = PhantomThemeMode.AUTO,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = themeScheme(mode, isSystemInDarkTheme()), content = content)
}
