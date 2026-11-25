package xyz.wraithnet.phantom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
    primary = Color(0xFFC0C9D2),
    onPrimary = Color(0xFF20252B),
    primaryContainer = Color(0xFF464D56),
    onPrimaryContainer = Color(0xFFF0F1F3),
    secondary = Color(0xFFAEB4BC),
    onSecondary = Color(0xFF252930),
    background = Color(0xFF101113),
    onBackground = Color(0xFFF0F1F3),
    surface = Color(0xFF292B30),
    onSurface = Color(0xFFF0F1F3),
    surfaceVariant = Color(0xFF3C4046),
    onSurfaceVariant = Color(0xFFAEB4BC),
    outline = Color(0xFF454950),
    error = Color(0xFFFFB1B1)
)

private val NightColors = darkColorScheme(
    primary = Color(0xFF91C1ED),
    onPrimary = Color(0xFF132C42),
    primaryContainer = Color(0xFF355574),
    onPrimaryContainer = Color(0xFFE4EEF8),
    secondary = Color(0xFFA6B9CD),
    onSecondary = Color(0xFF26384A),
    background = Color(0xFF0C1520),
    onBackground = Color(0xFFE4EEF8),
    surface = Color(0xFF1D2E41),
    onSurface = Color(0xFFE4EEF8),
    surfaceVariant = Color(0xFF344A61),
    onSurfaceVariant = Color(0xFFA6B9CD),
    outline = Color(0xFF3B5066)
)

private val ForestColors = darkColorScheme(
    primary = Color(0xFF91C7AE),
    onPrimary = Color(0xFF163527),
    primaryContainer = Color(0xFF385849),
    onPrimaryContainer = Color(0xFFE2ECE6),
    secondary = Color(0xFFA5B8AF),
    onSecondary = Color(0xFF20362B),
    background = Color(0xFF0C1815),
    onBackground = Color(0xFFE2ECE6),
    surface = Color(0xFF1B2D26),
    onSurface = Color(0xFFE2ECE6),
    surfaceVariant = Color(0xFF30483C),
    onSurfaceVariant = Color(0xFFA5B8AF),
    outline = Color(0xFF3B5147),
    error = Color(0xFFFFB6AD)
)

// Soft white background, silver-gray subscription surfaces.
private val LightColors = lightColorScheme(
    primary = Color(0xFF326C5D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBBCFC7),
    onPrimaryContainer = Color(0xFF26343B),
    secondary = Color(0xFF657078),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF293239),
    surface = Color(0xFFDCE0E4),
    onSurface = Color(0xFF293239),
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
        surfaceTint = base.primary,
        outlineVariant = base.outline,
        secondaryContainer = base.surfaceVariant,
        onSecondaryContainer = base.onSurface,
        tertiary = if (base.background.luminance() > .5f) Color(0xFF785920) else Color(0xFFE0C28A),
        onTertiary = if (base.background.luminance() > .5f) Color.White else Color(0xFF302719),
        tertiaryContainer = base.surfaceVariant,
        onTertiaryContainer = base.onSurface,
        inverseSurface = base.onSurface,
        inverseOnSurface = base.surface
    )
}

// Shared subscription depth: inset panel darker than card, pill lighter than panel.
internal fun ColorScheme.subscriptionPanelColor(): Color =
    if (surface.luminance() > .5f) surfaceVariant else lerp(surface, background, .58f)

internal fun ColorScheme.subscriptionPillColor(): Color =
    if (surface.luminance() > .5f) Color(0xFFE8ECEF) else surfaceVariant

// The floating capsule uses the approved card palette, separated by page margins.
internal fun ColorScheme.phantomNavigationColor(): Color =
    surface

// White remains genuinely white; dark themes use a restrained vertical gradient.
internal fun ColorScheme.phantomPageTop(): Color = when (background) {
    LightColors.background -> background
    GraphiteColors.background -> Color(0xFF202226)
    NightColors.background -> Color(0xFF17283B)
    ForestColors.background -> Color(0xFF142720)
    else -> lerp(background, surface, .55f)
}

internal fun ColorScheme.phantomPageBrush(): Brush =
    Brush.verticalGradient(0f to phantomPageTop(), .65f to background, 1f to background)

// The illumination is inside the clipped power circle only.
internal fun ColorScheme.powerCenter(connected: Boolean): Color =
    if (!connected) surface else when (background) {
        LightColors.background -> Color(0xFFAFD8C7)
        GraphiteColors.background -> Color(0xFF58636F)
        NightColors.background -> Color(0xFF426D99)
        ForestColors.background -> Color(0xFF3C6A54)
        else -> lerp(primaryContainer, primary, .22f)
    }

internal fun ColorScheme.powerMiddle(connected: Boolean): Color =
    if (connected) primaryContainer else surface

internal fun powerInteriorBrush(center: Color, middle: Color, edge: Color): Brush =
    Brush.radialGradient(0f to center, .48f to middle, 1f to edge)

internal fun ColorScheme.phantomSystemNavigationColor(): Color = background

@Composable
fun PhantomTheme(
    mode: PhantomThemeMode = PhantomThemeMode.AUTO,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = themeScheme(mode, isSystemInDarkTheme()), content = content)
}
