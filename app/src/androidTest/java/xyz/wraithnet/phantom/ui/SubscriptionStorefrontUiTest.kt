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
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.ui.home.SubscriptionPage

@RunWith(AndroidJUnit4::class)
class SubscriptionStorefrontUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun subscriptionSectionHasOnePurchaseActionWithoutAnAccountForm() {
        var opens = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { SubscriptionPage { opens++ } } } }
        compose.onAllNodesWithText("Купить подписку").assertCountEquals(1)
        compose.onNodeWithText("Купить подписку").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        for (extra in listOf("Пароль", "Электронная почта", "Ваш доступ", "В магазин", "Личный кабинет"))
            compose.onNodeWithText(extra).assertDoesNotExist()
    }

    @Test fun ownSubscriptionShowsAccountAndOpensEachActionIndependently() {
        val subscription = Subscription(
            id = "owned", title = "Phantom", source = "https://sub2.wraithnet.xyz/abcdefgh12345678",
            servers = emptyList(), accountTitle = "Имя из подписки", expiresAt = 1L, totalBytes = 0L,
        )
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    SubscriptionPage(subscription, "Имя с сервера",
                        openDevices = { actions += "devices" },
                        openRenewal = { actions += "renew" },
                        openShop = { actions += "shop" },
                    )
                }
            }
        }
        for (text in listOf("Ваш доступ", "Имя с сервера", "Срок истёк", "Лимит: Без лимита")) {
            compose.onNodeWithText(text).assertExists()
        }
        compose.onNodeWithContentDescription("Управление подключёнными устройствами").performScrollTo().performClick()
        compose.onNodeWithText("Продлить подписку").performScrollTo().performClick()
        compose.onNodeWithText("В магазин").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("devices", "renew", "shop"), actions) }
    }

    @Test fun otherProvidersShowMetadataWithoutPhantomAccountActions() {
        val subscription = Subscription(
            id = "external", title = "Другой провайдер", source = "https://example.org/subscription",
            servers = emptyList(),
        )
        compose.setContent {
            MaterialTheme {
                SubscriptionPage(subscription, openDevices = { error("Unexpected device access") },
                    openRenewal = { error("Unexpected renewal") }, openShop = {})
            }
        }
        compose.onNodeWithText(subscription.title).assertExists()
        compose.onNodeWithText("Продлить подписку").assertDoesNotExist()
        compose.onNodeWithContentDescription("Управление подключёнными устройствами").assertDoesNotExist()
        compose.onNodeWithText("В магазин").assertExists()
    }
}
