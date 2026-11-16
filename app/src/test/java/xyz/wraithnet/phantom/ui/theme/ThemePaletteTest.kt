package xyz.wraithnet.phantom.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ThemePaletteTest {
    @Test fun autoUsesSystemAppearance() {
        assertEquals(themeScheme(PhantomThemeMode.LIGHT, false).background,
            themeScheme(PhantomThemeMode.AUTO, false).background)
        assertEquals(themeScheme(PhantomThemeMode.GRAPHITE, true).background,
            themeScheme(PhantomThemeMode.AUTO, true).background)
    }

    @Test fun previewCardsUseDifferentActualPalettes() {
        assertNotEquals(themeScheme(PhantomThemeMode.LIGHT, true).background,
            themeScheme(PhantomThemeMode.GRAPHITE, true).background)
        assertNotEquals(themeScheme(PhantomThemeMode.GRAPHITE, true).background,
            themeScheme(PhantomThemeMode.NIGHT, true).background)
    }
}
