package xyz.wraithnet.phantom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
import xyz.wraithnet.phantom.data.SubscriptionDevicesApi
import xyz.wraithnet.phantom.ui.home.NativeDevicesScreen
import xyz.wraithnet.phantom.ui.home.SubscriptionAccessCard

@RunWith(AndroidJUnit4::class)
class SubscriptionStorefrontUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ownSubscriptionShowsAccountAndOpensEachActionIndependently() {
        val subscription = Subscription(
            id = "owned", title = "Phantom", source = "https://sub2.wraithnet.xyz/abcdefgh12345678",
            servers = emptyList(), accountTitle = "Имя из подписки", expiresAt = 1L, totalBytes = 0L,
        )
        val actions = mutableListOf<String>()
        val rejection = mutableStateOf<String?>(null)
        val devicesOpen = mutableStateOf(false)
        val snapshot = SubscriptionDevicesApi.Snapshot("Имя с сервера", 0, 3, false, emptyList())
        compose.setContent {
            MaterialTheme {
                if (devicesOpen.value) {
                    NativeDevicesScreen(subscription, "test-device", back = { devicesOpen.value = false },
                        load = { source, _ ->
                            assertEquals(subscription.source, source)
                            snapshot
                        },
                    )
                } else Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    SubscriptionAccessCard(subscription, "Имя с сервера",
                        openDevices = { actions += "devices"; devicesOpen.value = true },
                        openRenewal = {
                            actions += "renew"
                            rejection.value = "В магазине нет тарифа для продления этой подписки."
                        },
                        renewalUnavailable = rejection.value,
                    )
                }
            }
        }
        for (text in listOf("Ваш доступ", "Имя с сервера", "Срок истёк", "Лимит: Без лимита")) {
            compose.onNodeWithText(text).assertExists()
        }
        compose.onNodeWithContentDescription("Управление подключёнными устройствами").performScrollTo().performClick()
        compose.onNodeWithText("Подключённые устройства: 0 из 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Продлить подписку").performScrollTo().performClick()
        compose.onNodeWithText("В магазине нет тарифа для продления этой подписки.").assertExists()
        compose.onNodeWithText("Продлить подписку").assertDoesNotExist()
        compose.onNodeWithContentDescription("Управление подключёнными устройствами").assertExists()
        compose.runOnIdle { assertEquals(listOf("devices", "renew"), actions) }
    }

    @Test fun otherProvidersShowMetadataWithoutPhantomAccountActions() {
        val subscription = Subscription(
            id = "external", title = "Другой провайдер", source = "https://example.org/subscription",
            servers = emptyList(),
        )
        compose.setContent {
            MaterialTheme {
                SubscriptionAccessCard(subscription, openDevices = { error("Unexpected device access") },
                    openRenewal = { error("Unexpected renewal") })
            }
        }
        compose.onNodeWithText(subscription.title).assertExists()
        compose.onNodeWithText("Продлить подписку").assertDoesNotExist()
        compose.onNodeWithContentDescription("Управление подключёнными устройствами").assertDoesNotExist()
    }
}
