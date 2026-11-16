package xyz.wraithnet.phantom.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ThemePaletteTest {
    @Test fun autoUsesSystemAppearance() {
        assertEquals(themeScheme(PhantomThemeMode.LIGHT, false).background,
            themeScheme(PhantomThemeMode.AUTO, false).background)
        assertNotEquals(themeScheme(PhantomThemeMode.GRAPHITE, true).background,
            themeScheme(PhantomThemeMode.AUTO, true).background)
    }

    @Test fun previewCardsUseDifferentActualPalettes() {
        assertNotEquals(themeScheme(PhantomThemeMode.LIGHT, true).background,
            themeScheme(PhantomThemeMode.GRAPHITE, true).background)
        assertNotEquals(themeScheme(PhantomThemeMode.GRAPHITE, true).background,
            themeScheme(PhantomThemeMode.NIGHT, true).background)
        assertNotEquals(themeScheme(PhantomThemeMode.FOREST, true).background,
            themeScheme(PhantomThemeMode.GRAPHITE, true).background)
        assertNotEquals(themeScheme(PhantomThemeMode.AUTO, true).background,
            themeScheme(PhantomThemeMode.NIGHT, true).background)
        assertNotEquals(themeScheme(PhantomThemeMode.LIGHT, false).surface,
            androidx.compose.ui.graphics.Color.White)
    }
}
