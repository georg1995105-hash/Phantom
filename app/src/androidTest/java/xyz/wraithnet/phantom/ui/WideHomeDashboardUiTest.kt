package xyz.wraithnet.phantom.ui

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

@RunWith(AndroidJUnit4::class)
class WideHomeDashboardUiTest {
    @get:Rule val compose = createEmptyComposeRule()

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
            compose.awaitVisible(hasText("Серверы"))
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
