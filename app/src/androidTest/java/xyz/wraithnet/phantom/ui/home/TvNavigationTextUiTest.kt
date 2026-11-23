package xyz.wraithnet.phantom.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real text layout regression: semantics still reports the FULL string even if
 * Compose draws it clipped. Check the measured glyph overflow, not merely
 * whether a node with the label exists.
 */
@RunWith(AndroidJUnit4::class)
class TvNavigationTextUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allRemoteNavigationTitlesFitWithIncreasedTvTextScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                Configuration.UI_MODE_TYPE_TELEVISION
            fontScale = 1.35f
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(context.resources.displayMetrics.density, 1.35f)
            ) {
                MaterialTheme {
                    Box(Modifier.height(560.dp)) {
                        TvSideNavigation(Destination.SETTINGS, select = {})
                    }
                }
            }
        }
        for (label in listOf("Главная", "Подписка", "Настройки")) {
            val node = compose.onNode(hasText(label), useUnmergedTree = true)
                .fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>()
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                ?: error("Text layout is missing for TV rail: $label")
            assertTrue("Unable to read real text layout for $label", action(layout))
            assertTrue("No layout result for $label", layout.isNotEmpty())
            assertFalse("TV navigation truncates $label at font scale 1.35",
                layout.first().hasVisualOverflow)
        }
    }
}
