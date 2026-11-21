package xyz.wraithnet.phantom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.ui.home.SubscriptionPage
import xyz.wraithnet.phantom.data.Subscription

@RunWith(AndroidJUnit4::class)
class SubscriptionStorefrontUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneStoreButtonAndNoDuplicateActionsOnNarrowScreen() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    SubscriptionPage { opens++ }
                }
            }
        }
        compose.onAllNodesWithText("В магазин").assertCountEquals(1)
        compose.onNodeWithText("В магазин").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        for (extra in listOf("Купить", "Продлить", "Личный кабинет", "Мои подписки", "Поддержка")) {
            compose.onNodeWithText(extra).assertDoesNotExist()
        }
    }
    @Test fun importedSubscriptionIsReadOnlyAndKeepsShopEntryPoint() {
        val sub = Subscription(id = "imported", title = "Моя тестовая подписка",
            source = "https://example.org/subscription", servers = emptyList(),
            expiresAt = System.currentTimeMillis() / 1000L + 86400L * 5L,
            uploadBytes = 1024L, downloadBytes = 2048L, totalBytes = 10240L)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    SubscriptionPage(currentSubscription = sub) {}
                }
            }
        }
        compose.onNodeWithText("Моя тестовая подписка").assertExists()
        compose.onNodeWithText("Данные получены от сервера подписки").assertExists()
        compose.onAllNodesWithText("В магазин").assertCountEquals(1)
    }

}
