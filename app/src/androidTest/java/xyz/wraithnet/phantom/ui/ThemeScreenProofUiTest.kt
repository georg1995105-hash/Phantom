package xyz.wraithnet.phantom.ui

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.ui.home.*
import xyz.wraithnet.phantom.ui.theme.PhantomTheme
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode
import xyz.wraithnet.phantom.ui.theme.phantomPageBrush

@RunWith(AndroidJUnit4::class)
class ThemeScreenProofUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    // Synthetic profiles only. Actual production Compose components, no VPN
    // connection, provider requests, real subscription URLs or user data.
    @Test fun capturePhoneAndWideLayoutInEveryTheme() {
        val servers = listOf("Нидерланды", "Германия", "Финляндия", "Польша").mapIndexed { i, name ->
            ServerProfile("proof-$i", name, "vless",
                raw = "vless://00000000-0000-0000-0000-000000000001@test.example:443?type=xhttp&security=reality#$name")
        }
        val now = System.currentTimeMillis()
        val first = Subscription("theme-proof-first", "Тестовая подписка", "https://example.com/first", servers,
            updatedAt = now, downloadBytes = 2200000000000L, totalBytes = 0L,
            expiresAt = now / 1000 + 365 * 86400L,
            webPageUrl = "https://example.com/account", supportUrl = "https://example.com/support")
        val second = first.copy(id = "theme-proof-second", title = "Бессрочная подписка",
            source = "https://example.com/second", expiresAt = 0L)
        val selected = first to servers[2]
        val owners = listOf(first, second)
        val latency = servers.associate { "${first.id}:${it.id}" to 60L }
        var theme by mutableStateOf(PhantomThemeMode.LIGHT)
        var wide by mutableStateOf(false)
        var tab by mutableStateOf(Destination.HOME)
        var connected by mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val configuration = Configuration(activity.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_NORMAL
                        screenWidthDp = if (wide) 800 else 360
                        smallestScreenWidthDp = if (wide) 800 else 360
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration) {
                        PhantomTheme(theme) {
                            val colors = MaterialTheme.colorScheme
                            Scaffold(containerColor = colors.background,
                                bottomBar = { TabBar(tab) { tab = it } }) { padding ->
                                Box(Modifier.fillMaxSize().padding(padding).background(colors.phantomPageBrush())) {
                                    if (wide) {
                                        WideHomeDashboard(status = if (connected) "connected" else "disconnected", selected = selected,
                                            download = 0L, upload = 0L, seconds = 175L,
                                            subscriptions = owners, pinnedId = null,
                                            favorites = emptySet(), latency = latency, measuring = emptyMap(),
                                            refreshing = emptySet(), probing = emptySet(), failed = emptySet(),
                                            importing = false, focusEpoch = 0, modalVisible = false,
                                            onToggle = {}, onSelect = { _, _ -> }, onRefresh = {},
                                            onManage = {}, onProbe = {}, onManualPing = {}, onFavorite = {},
                                            onImport = {}, onPairQr = null, onScanQr = null,
                                            onShowError = null, onOpenUrl = {})
                                    } else {
                                        ServersPage(subscriptions = owners, selected = selected, pinnedId = null,
                                            favorites = emptySet(), latency = latency, probing = emptySet(),
                                            measuring = emptyMap(), refreshing = emptySet(), failed = emptySet(),
                                            onRefresh = {}, manage = {}, onAutoProbe = {}, onManualPing = {},
                                            favorite = {}, choose = { _, _ -> }, openUrl = {},
                                            header = {
                                                PageTitle(Destination.HOME)
                                                ConnectionPage(status = if (connected) "connected" else "disconnected", seconds = 175L,
                                                    toggle = {}, error = null, hasSubscriptions = true, embedded = true)
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (isWide in listOf(false, true)) {
                for (mode in listOf(PhantomThemeMode.LIGHT, PhantomThemeMode.GRAPHITE,
                        PhantomThemeMode.NIGHT, PhantomThemeMode.FOREST)) {
                    compose.runOnIdle { theme = mode; wide = isWide; connected = true; tab = Destination.HOME }
                    compose.onNodeWithContentDescription("Вкладка Главная").assertIsDisplayed().assertIsSelected()
                    for (name in listOf("Подписка", "Настройки", "Главная")) {
                        compose.onNodeWithContentDescription("Вкладка $name").performClick().assertIsSelected()
                    }
                    compose.onNodeWithText("Тестовая подписка").assertExists()
                    val prefix = "${if (isWide) "wide-layout" else "phone"}-${mode.name.lowercase()}"
                    compose.onNodeWithText("Подключено", useUnmergedTree = true).assertIsDisplayed()
                    val on = capture("$prefix-on.png")
                    try {
                        compose.runOnIdle { connected = false }
                        compose.onNodeWithText("Отключено", useUnmergedTree = true).assertIsDisplayed()
                        compose.onNodeWithText("00:02:55", useUnmergedTree = true).assertDoesNotExist()
                        val off = capture("$prefix-off.png")
                        try {
                            assertFalse("Connected and disconnected renders must differ: $prefix", on.sameAs(off))
                        } finally {
                            off.recycle()
                        }
                    } finally {
                        on.recycle()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun capture(name: String): Bitmap {
        // CI uses API 35. MediaStore keeps these synthetic screenshots after the
        // test APK is uninstalled; the emulator runner pulls them before exit.
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Capture the synchronized Compose root, not a possibly stale compositor buffer.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        if (Build.VERSION.SDK_INT < 29) return bitmap
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhantomThemeProof")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
        return bitmap
    }
}
