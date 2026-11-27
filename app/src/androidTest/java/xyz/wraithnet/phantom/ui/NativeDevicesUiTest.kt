package xyz.wraithnet.phantom.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionDevicesApi
import xyz.wraithnet.phantom.ui.home.NativeDevicesScreen

@RunWith(AndroidJUnit4::class)
class NativeDevicesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listAndRemovalStayInsideAppWithConfirmationAndVerifiedRefresh() {
        val sub = Subscription(id = "manual", title = "Phantom VPN",
            source = "https://sub2.wraithnet.xyz/abcdefgh12345678", servers = emptyList())
        val first = SubscriptionDevicesApi.Device(
            id = "a".repeat(64), platform = "Android", model = "vivo V2514",
            osVersion = "16", app = "Happ", addedAt = "2026-11-20T01:00:00Z",
            seenAt = null, current = false)
        val second = SubscriptionDevicesApi.Device(
            id = "b".repeat(64), platform = "Android", model = "Tablet",
            osVersion = "15", app = "Phantom", addedAt = "2026-11-21T01:00:00Z",
            seenAt = null, current = true)
        var snapshot = SubscriptionDevicesApi.Snapshot(
            title = "Phantom VPN — ADMIN", count = 2, limit = 3,
            canDelete = true, devices = listOf(first, second))
        var deletes = 0
        var back = 0
        compose.setContent {
            MaterialTheme {
                NativeDevicesScreen(
                    sub, "local-id-phantom",
                    back = { back++ },
                    load = { _, _ -> snapshot },
                    remove = { _, _, id ->
                        deletes++
                        snapshot = snapshot.copy(
                            devices = snapshot.devices.filterNot { it.id == id },
                            count = 1)
                        snapshot
                    }
                )
            }
        }
        compose.onNodeWithText("Phantom VPN — ADMIN").assertExists()
        compose.onNodeWithText("vivo V2514").assertExists()
        compose.onAllNodesWithText("Отключить устройство").onFirst().performClick()
        compose.onNodeWithText("Отключить устройство?").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onAllNodesWithText("Отключить устройство").onFirst().performClick()
        compose.onNodeWithText("Отключить", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Устройство отключено. Место освобождено.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(1, deletes) }
        compose.onNodeWithText("vivo V2514").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }
}
