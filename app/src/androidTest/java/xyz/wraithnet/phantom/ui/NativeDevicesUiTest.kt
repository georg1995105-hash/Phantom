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
                            count = 1, sessionsRefreshed = true)
                        snapshot
                    }
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("vivo V2514").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Phantom VPN — ADMIN").assertExists()
        compose.onNodeWithText("vivo V2514").assertExists()
        compose.onAllNodesWithText("Удалить устройство").onFirst().performClick()
        compose.onNodeWithText("Удалить устройство?").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onAllNodesWithText("Удалить устройство").onFirst().performClick()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Устройство удалено.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(1, deletes) }
        compose.onNodeWithText("vivo V2514").assertDoesNotExist()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test fun cachedListAndBulkRemovalUseOneConfirmedRequest() {
        val sub = Subscription(id = "manual", title = "Phantom VPN",
            source = "https://sub2.wraithnet.xyz/abcdefgh12345678", servers = emptyList())
        val devices = (1..19).map { index ->
            SubscriptionDevicesApi.Device(
                id = index.toString(16).padStart(64, '0'), platform = "Android",
                model = "Device " + index, osVersion = "16", app = "Phantom",
                addedAt = null, seenAt = null, current = index == 19)
        }
        val snapshot = SubscriptionDevicesApi.Snapshot(
            title = "Phantom VPN — ADMIN", count = devices.size, limit = null,
            canDelete = true, devices = devices, canDeleteAll = true)
        var bulk = 0
        var singles = 0
        compose.setContent {
            MaterialTheme {
                NativeDevicesScreen(sub, "local-id-phantom", back = {},
                    initialSnapshot = snapshot,
                    initialLoad = { _, _ -> snapshot },
                    load = { _, _ -> snapshot },
                    remove = { _, _, _ -> singles++; error("Unexpected single removal") },
                    removeAll = { source, _, ids ->
                        assertEquals(sub.source, source)
                        assertEquals(devices.map { it.id }, ids)
                        bulk++
                        snapshot.copy(count = 0, devices = emptyList(), sessionsRefreshed = true)
                    })
            }
        }
        compose.onNodeWithText("Phantom VPN — ADMIN").assertExists()
        compose.onNodeWithText("Удалить все устройства").performClick()
        compose.onNodeWithText("Удалить все устройства?").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, bulk) }
        compose.onNodeWithText("Удалить все устройства").performClick()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Все устройства удалены.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(1, bulk)
            assertEquals(0, singles)
        }
        compose.onNodeWithText("В этой подписке пока нет зарегистрированных устройств.").assertExists()
        compose.onNodeWithText("Удалить все устройства").assertDoesNotExist()
    }
    @Test fun failedRefreshDoesNotDisableConfirmedDevices() {
        val sub = Subscription(id = "manual", title = "Phantom VPN",
            source = "https://sub2.wraithnet.xyz/abcdefgh12345678", servers = emptyList())
        val device = SubscriptionDevicesApi.Device(
            id = "a".repeat(64), platform = "Android", model = "Tablet",
            osVersion = "16", app = "Phantom", addedAt = null, seenAt = null, current = false)
        val snapshot = SubscriptionDevicesApi.Snapshot(
            title = "Phantom VPN — ADMIN", count = 1, limit = null,
            canDelete = true, devices = listOf(device), canDeleteAll = true)
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                NativeDevicesScreen(sub, "local-id-phantom", back = {},
                    initialSnapshot = snapshot,
                    initialLoad = { _, _ -> error("Ошибка загрузки") },
                    remove = { _, _, id ->
                        assertEquals(device.id, id)
                        deletes++
                        snapshot.copy(count = 0, devices = emptyList(), sessionsRefreshed = true)
                    })
            }
        }
        compose.onNodeWithText("Ошибка загрузки").assertExists()
        compose.onNodeWithText("Удалить все устройства").assertIsEnabled()
        compose.onNodeWithText("Удалить устройство").assertIsEnabled().performClick()
        compose.onNodeWithText("Удалить устройство?").assertExists()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Устройство удалено.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(1, deletes) }
        compose.onNodeWithText("Tablet").assertDoesNotExist()
    }

}
