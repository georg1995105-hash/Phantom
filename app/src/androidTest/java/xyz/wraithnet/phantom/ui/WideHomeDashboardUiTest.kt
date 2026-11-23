package xyz.wraithnet.phantom.ui

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.ui.home.WideHomeDashboard
import xyz.wraithnet.phantom.ui.theme.PhantomTheme
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode
import xyz.wraithnet.phantom.ui.theme.themeScheme

@RunWith(AndroidJUnit4::class)
class WideHomeDashboardUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun forestTabletUsesGreenActiveImportAndScannerButtons() {
        var importClicks by mutableStateOf(0)
        var scanClicks by mutableStateOf(0)
        val tabletServers = listOf(
            ServerProfile("nl-tablet", "Нидерланды", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000011@one.example:443#NL"),
            ServerProfile("pl-tablet", "Польша", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000012@two.example:443#PL"),
        )
        val tabletOwner = Subscription("tablet-owner", "Любой провайдер",
            "https://example.com/servers", tabletServers,
            updatedAt = System.currentTimeMillis())
        val palette = themeScheme(PhantomThemeMode.FOREST, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tablet = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 800
                    screenHeightDp = 600
                    smallestScreenWidthDp = 800
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                        Configuration.UI_MODE_TYPE_NORMAL
                }
                activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides tablet) {
                        PhantomTheme(mode = PhantomThemeMode.FOREST) {
                            WideHomeDashboard(
                                status = "disconnected", selected = null,
                                download = 0L, upload = 0L, seconds = 0L,
                                subscriptions = listOf(tabletOwner), pinnedId = null,
                                favorites = emptySet(), latency = emptyMap(),
                                measuring = emptyMap(), refreshing = emptySet(),
                                probing = emptySet(), failed = emptySet(),
                                importing = false, focusEpoch = 0, modalVisible = false,
                                onToggle = {}, onSelect = { _, _ -> },
                                onRefresh = {}, onManage = {}, onProbe = {},
                                onManualPing = {}, onFavorite = {},
                                onImport = { importClicks++ },
                                onPairQr = null, onScanQr = { scanClicks++ },
                                onShowError = null, onOpenUrl = {},
                            )
                        }
                    }
                }
            }
            compose.onAllNodes(hasText("Серверы")).assertCountEquals(0)
            val list = compose.onNode(hasScrollAction())
            list.performScrollToNode(
                hasContentDescription("Выбрать сервер Нидерланды"))
            val first = compose.awaitVisible(
                hasContentDescription("Выбрать сервер Нидерланды")).getUnclippedBoundsInRoot()
            val second = compose.awaitVisible(
                hasContentDescription("Выбрать сервер Польша")).getUnclippedBoundsInRoot()
            assertTrue("Tablet must have ONE server per row",
                second.top > first.bottom)
            assertTrue("Tablet servers must use the same full-width column",
                kotlin.math.abs(first.left.value - second.left.value) < 4f)
            assertTrue("Tablet server cards must stay aligned at the right edge",
                kotlin.math.abs(first.right.value - second.right.value) < 4f)
            list.performScrollToNode(hasContentDescription("Добавить подписку или ключ"))
            val add = compose.awaitVisible(
                hasContentDescription("Добавить подписку или ключ"))
            val scan = compose.awaitVisible(hasContentDescription("Сканировать QR"))
            add.assertIsEnabled()
            scan.assertIsEnabled()
            val addBitmap = add.captureToImage().toPixelMap()
            val scanBitmap = scan.captureToImage().toPixelMap()
            // Inspect the empty upper interior of each large button, not text
            // or antialiased rounded corners. A neutral M3 tonal gray is a
            // regression regardless of whether the button still clicks.
            val addFill = addBitmap[addBitmap.width / 2, addBitmap.height / 4]
            val scanFill = scanBitmap[scanBitmap.width / 2, scanBitmap.height / 4]
            assertTrue("Import must use theme primary green: $addFill",
                kotlin.math.abs(addFill.red - palette.primary.red) < .10f &&
                kotlin.math.abs(addFill.green - palette.primary.green) < .10f &&
                kotlin.math.abs(addFill.blue - palette.primary.blue) < .10f)
            assertTrue("QR must use theme primaryContainer green: $scanFill",
                kotlin.math.abs(scanFill.red - palette.primaryContainer.red) < .10f &&
                kotlin.math.abs(scanFill.green - palette.primaryContainer.green) < .10f &&
                kotlin.math.abs(scanFill.blue - palette.primaryContainer.blue) < .10f)
            add.performClick()
            scan.performClick()
            compose.runOnIdle {
                assertEquals(1, importClicks)
                assertEquals(1, scanClicks)
            }
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        }
    }

    @Test fun tvHomeShowsTwoServerColumnsAndBottomQrWithoutAnotherPage() {
        val entries = listOf(
            ServerProfile("de", "Германия", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000001@one.example:443#Germany"),
            ServerProfile("pl", "Польша", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000002@two.example:443#Poland"),
            ServerProfile("se", "Швеция", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000003@three.example:443#Sweden"),
        )
        val owner = Subscription("wide-test", "Phantom VPN", "https://example.com/sub",
            entries, updatedAt = System.currentTimeMillis(), totalBytes = 0L)
        var selected by mutableStateOf(entries.first())
        var qr by mutableStateOf(false)
        var imported by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tvConfig = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                        Configuration.UI_MODE_TYPE_TELEVISION
                }
                activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides tvConfig) {
                        PhantomTheme {
                            WideHomeDashboard(
                                status = "connected", selected = owner to selected,
                                download = 4096, upload = 2048, seconds = 100,
                                subscriptions = listOf(owner), pinnedId = null,
                                favorites = emptySet(), latency = emptyMap(),
                                measuring = emptyMap(), refreshing = emptySet(),
                                probing = emptySet(), failed = emptySet(),
                                importing = false, focusEpoch = 0, modalVisible = false,
                                onToggle = {}, onSelect = { _, item -> selected = item },
                                onRefresh = {}, onManage = {}, onProbe = {},
                                onManualPing = {}, onFavorite = {},
                                onImport = { imported = true },
                                onPairQr = { qr = true }, onScanQr = null,
                                onShowError = null, onOpenUrl = {},
                            )
                        }
                    }
                }
            }
            compose.onAllNodes(hasText("Серверы")).assertCountEquals(0)
            // Wide TV now starts with the power/selected connection and
            // immediately follows with the subscription, without a counter.
            val one = compose.awaitVisible(
                hasContentDescription("Выбрать сервер Германия")).getUnclippedBoundsInRoot()
            val two = compose.awaitVisible(
                hasContentDescription("Выбрать сервер Польша")).getUnclippedBoundsInRoot()
            // Independent same-row cards: one continuous LazyColumn, no inner grid scroll.
            assertTrue(kotlin.math.abs(one.top.value - two.top.value) < 4f)
            assertTrue(one.right <= two.left)
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            compose.awaitVisible(hasContentDescription("Выбрать сервер Польша")).performClick()
            compose.runOnIdle { assertEquals("pl", selected.id) }
            val list = compose.onNode(hasScrollAction())
            list.performScrollToNode(hasText("QR с телефона"))
            compose.awaitVisible(hasText("QR с телефона")).performClick()
            compose.runOnIdle { assertTrue(qr) }
            list.performScrollToNode(hasText("Добавить подписку или ключ"))
            compose.awaitVisible(hasText("Добавить подписку или ключ")).performClick()
            compose.runOnIdle { assertTrue(imported) }
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        }
    }
}
