package xyz.wraithnet.phantom.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionStore

@RunWith(AndroidJUnit4::class)
class UnifiedHomeScrollUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun phoneHasOneScrollingPageWithCompleteIndependentServerGroups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SubscriptionStore(context)
        val original = store.read()
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val priorSub = prefs.getString("selected_subscription_id", null)
        val priorServer = prefs.getString("selected_server_id", null)
        val now = System.currentTimeMillis()
        val first = Subscription("unified-one", "Alpha VPN", "https://alpha.example/sub",
            listOf(ServerProfile("one", "Нидерланды тест", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000001@alpha.example:443#Netherlands")),
            updatedAt = now, totalBytes = 0L)
        val second = Subscription("unified-two", "Beta VPN", "https://beta.example/sub",
            listOf(ServerProfile("two", "Германия тест", "hysteria2",
                raw = "hysteria2://test:secret@beta.example:443#Germany")),
            updatedAt = now)
        try {
            store.write(listOf(first, second))
            prefs.edit().putString("selected_subscription_id", first.id)
                .putString("selected_server_id", "one").commit()
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.awaitVisible(hasContentDescription("Вкладка Главная"))
                compose.onAllNodes(hasContentDescription("Вкладка Серверы")).assertCountEquals(0)
                compose.awaitVisible(hasContentDescription("Вкладка Подписка"))
                compose.awaitVisible(hasContentDescription("Вкладка Настройки"))
                compose.waitUntil(15_000) {
                    compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size == 1
                }
                val wholePage = compose.onNode(hasScrollAction())
                wholePage.performScrollToNode(hasText("Alpha VPN"))
                compose.awaitVisible(hasText("Alpha VPN"))
                compose.awaitVisible(hasText("Обновлено", substring = true))
                wholePage.performScrollToNode(hasContentDescription("Выбрать сервер Нидерланды тест"))
                compose.awaitVisible(hasContentDescription("Выбрать сервер Нидерланды тест"))
                wholePage.performScrollToNode(hasText("Beta VPN"))
                compose.awaitVisible(hasText("Beta VPN"))
                compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
                compose.awaitVisible(hasContentDescription("Вкладка Подписка"))
                    .performClick()
                compose.awaitVisible(hasText("В магазин"))
                compose.awaitVisible(hasContentDescription("Вкладка Главная"))
                    .performClick()
                compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
                wholePage.performScrollToNode(hasText("Alpha VPN"))
                compose.awaitVisible(hasText("Alpha VPN"))
            }
        } finally {
            store.write(original)
            prefs.edit().putString("selected_subscription_id", priorSub)
                .putString("selected_server_id", priorServer).commit()
            assertEquals(original.size, store.read().size)
        }
    }
}
