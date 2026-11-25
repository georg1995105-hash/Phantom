package xyz.wraithnet.phantom.ui

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.ui.home.ProviderInfo
import xyz.wraithnet.phantom.ui.theme.PhantomTheme
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode

@RunWith(AndroidJUnit4::class)
class SubscriptionMetadataPanelUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun compactMetadataKeepsActionsAndDetailsInEveryPalette() {
        val owner = Subscription("metadata-test", "Phantom VPN", "https://example.com/sub",
            emptyList(), uploadBytes = 0L, downloadBytes = 0L, totalBytes = 0L,
            expiresAt = 0L, webPageUrl = "https://example.com/account",
            supportUrl = "https://example.com/support", announcement = "Условия провайдера")
        var theme by mutableStateOf(PhantomThemeMode.LIGHT)
        var opened = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val phone = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320
                    smallestScreenWidthDp = 320
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                        Configuration.UI_MODE_TYPE_NORMAL
                }
                activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides phone) {
                        PhantomTheme(theme) {
                            var details by remember { mutableStateOf(false) }
                            Box(Modifier.widthIn(max = 288.dp)) {
                                ProviderInfo(owner, "Phantom VPN", details,
                                    { details = !details }, { opened = it })
                            }
                        }
                    }
                }
            }
            for (mode in listOf(PhantomThemeMode.LIGHT, PhantomThemeMode.GRAPHITE,
                    PhantomThemeMode.FOREST, PhantomThemeMode.NIGHT)) {
                compose.runOnIdle { theme = mode }
                compose.onNodeWithText("Трафик", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("0 Б / ∞", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("Бессрочно", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Кабинет Phantom VPN").performClick()
                compose.runOnIdle { assertEquals("https://example.com/account", opened) }
                compose.onNodeWithContentDescription("Поддержка Phantom VPN").performClick()
                compose.runOnIdle { assertEquals("https://example.com/support", opened) }
                val details = compose.onNodeWithContentDescription("Подробнее о Phantom VPN")
                details.performClick()
                compose.onNodeWithText("Условия провайдера").assertIsDisplayed()
                details.performClick()
                compose.onNodeWithText("Условия провайдера").assertDoesNotExist()
            }
        }
    }
}
