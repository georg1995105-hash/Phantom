package xyz.wraithnet.phantom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhantomColorScheme = darkColorScheme(
    primary = Color(0xFF5B7FFF),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3D4F99),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF0F1420),
    onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF1A2033),
    onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF252C42),
    onSurfaceVariant = Color(0xFFB0B8CC),
    outline = Color(0xFF3A4466),
)

@Composable
fun PhantomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhantomColorScheme,
        content = content
    )
}
