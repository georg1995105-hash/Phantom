package xyz.wraithnet.phantom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

enum class PhantomThemeMode(val title: String) {
    AUTO("Авто"),
    LIGHT("Светлая"),
    GRAPHITE("Графит"),
    NIGHT("Тёмно-синяя"),
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
    primary = Color(0xFF82B7A7),
    onPrimary = Color(0xFF172D26),
    primaryContainer = Color(0xFF34443F),
    onPrimaryContainer = Color(0xFFD8E5DF),
    secondary = Color(0xFFA5AFAC),
    onSecondary = Color(0xFF26322E),
    background = Color(0xFF111214),
    onBackground = Color(0xFFD8DBDD),
    surface = Color(0xFF232528),
    onSurface = Color(0xFFD8DBDD),
    surfaceVariant = Color(0xFF303338),
    onSurfaceVariant = Color(0xFFA5ABB0),
    outline = Color(0xFF555A60),
    error = Color(0xFFFFB1B1)
)

private val NightColors = darkColorScheme(
    primary = Color(0xFF8BAED5),
    onPrimary = Color(0xFF14283D),
    primaryContainer = Color(0xFF2B405B),
    onPrimaryContainer = Color(0xFFD5DEE9),
    secondary = Color(0xFFA4B4C8),
    onSecondary = Color(0xFF27374B),
    background = Color(0xFF0D1420),
    onBackground = Color(0xFFD5DEE9),
    surface = Color(0xFF1C293B),
    onSurface = Color(0xFFD5DEE9),
    surfaceVariant = Color(0xFF2C3C51),
    onSurfaceVariant = Color(0xFFA4B4C8),
    outline = Color(0xFF4B6079)
)

private val ForestColors = darkColorScheme(
    primary = Color(0xFF80BCA6),
    onPrimary = Color(0xFF153328),
    primaryContainer = Color(0xFF294438),
    onPrimaryContainer = Color(0xFFD3E0D9),
    secondary = Color(0xFF9CB9AA),
    onSecondary = Color(0xFF193A2A),
    background = Color(0xFF0B1614),
    onBackground = Color(0xFFD3E0D9),
    surface = Color(0xFF1A2B26),
    onSurface = Color(0xFFD3E0D9),
    surfaceVariant = Color(0xFF293E34),
    onSurfaceVariant = Color(0xFFA1B6AC),
    outline = Color(0xFF436157),
    error = Color(0xFFFFB6AD)
)

// Soft white background, silver-gray subscription surfaces.
private val LightColors = lightColorScheme(
    primary = Color(0xFF38665B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCEDDD7),
    onPrimaryContainer = Color(0xFF304C43),
    secondary = Color(0xFF657078),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF343C43),
    surface = Color(0xFFDCE0E4),
    onSurface = Color(0xFF343C43),
    surfaceVariant = Color(0xFFC3CBD1),
    onSurfaceVariant = Color(0xFF3D4A52),
    outline = Color(0xFF85929D),
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

// Shared subscription depth: inset panel darker than card, pill lighter than panel.
internal fun ColorScheme.subscriptionPanelColor(): Color =
    if (surface.luminance() > .5f) surfaceVariant else lerp(surface, background, .58f)

internal fun ColorScheme.subscriptionPillColor(): Color =
    if (surface.luminance() > .5f) Color(0xFFE8ECEF) else surfaceVariant

// The fixed navigation is a separate depth layer, not another subscription card.
internal fun ColorScheme.phantomNavigationColor(): Color =
    if (surface.luminance() > .5f) Color(0xFFD2D8DE) else lerp(surface, background, .38f)

@Composable
fun PhantomTheme(
    mode: PhantomThemeMode = PhantomThemeMode.AUTO,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = themeScheme(mode, isSystemInDarkTheme()), content = content)
}
