package xyz.wraithnet.phantom.ui.theme

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ThemePaletteTest {
    @Test fun subscriptionPanelHasConsistentDepthAndReadableValues() {
        for (mode in PhantomThemeMode.entries) {
            for (dark in listOf(false, true)) {
                val c = themeScheme(mode, dark)
                val panel = c.subscriptionPanelColor()
                val pill = c.subscriptionPillColor()
                assertTrue("$mode panel must be darker than card", panel.luminance() < c.surface.luminance())
                assertTrue("$mode pill must be lighter than panel", pill.luminance() > panel.luminance())
                val a = c.onSurface.luminance()
                val b = pill.luminance()
                assertTrue("$mode pill text contrast", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f)
            }
        }
    }

    @Test fun lightHierarchyIsDistinctAndMetadataLabelsAreReadable() {
        val c = themeScheme(PhantomThemeMode.LIGHT, false)
        assertEquals(androidx.compose.ui.graphics.Color.White, c.background)
        assertTrue(c.background.luminance() - c.surface.luminance() >= .18f)
        assertTrue(c.surface.luminance() - c.subscriptionPanelColor().luminance() >= .10f)
        for (mode in PhantomThemeMode.entries) {
            for (dark in listOf(false, true)) {
                val s = themeScheme(mode, dark)
                val a = s.onSurfaceVariant.luminance()
                val b = s.subscriptionPanelColor().luminance()
                assertTrue("$mode metadata label contrast", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f)
                assertNotEquals(s.surface, s.phantomNavigationColor())
            }
        }
    }

    @Test fun autoUsesSystemAppearance() {
        assertEquals(themeScheme(PhantomThemeMode.LIGHT, false).background,
            themeScheme(PhantomThemeMode.AUTO, false).background)
        assertNotEquals(themeScheme(PhantomThemeMode.GRAPHITE, true).background,
            themeScheme(PhantomThemeMode.AUTO, true).background)
    }

    @Test fun dialogsAndMenusFollowEveryPalette() {
        for (mode in PhantomThemeMode.entries) {
            val scheme = themeScheme(mode, true)
            assertEquals(scheme.surface, scheme.surfaceContainer)
            assertEquals(scheme.surface, scheme.surfaceContainerHigh)
            assertEquals(scheme.surfaceVariant, scheme.surfaceContainerHighest)
        }
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
