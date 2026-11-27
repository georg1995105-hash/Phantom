package xyz.wraithnet.phantom.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionStore
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MultipleSubscriptionsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun serverGroupsAndRefreshButtonsKeepSeparateOwnersWithIdenticalServerIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SubscriptionStore(context)
        val saved = store.read()
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val oldSub = prefs.getString("selected_subscription_id", null)
        val oldServer = prefs.getString("selected_server_id", null)
        val oldPinned = prefs.getString("pinned_sub_id", null)
        val now = System.currentTimeMillis()
        val server = MockWebServer()
        val releaseResponses = CountDownLatch(1)
        val refreshedLink = "vless://00000000-0000-0000-0000-000000000003@c.example:443#Финляндия"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!releaseResponses.await(12, TimeUnit.SECONDS)) {
                    return MockResponse().setResponseCode(500)
                }
                return when (request.path) {
                    "/first" -> MockResponse().setBody(refreshedLink)
                    "/second" -> MockResponse().setResponseCode(503)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val endpoint = "http://127.0.0.1:${server.port}"
        val first = Subscription(
            id = "test-owner-one", title = "Тестовая подписка",
            source = "$endpoint/first",
            servers = listOf(
                ServerProfile("same-server-id", "Нидерланды тест", "vless",
                    raw = "vless://00000000-0000-0000-0000-000000000001@a.example:443#NL"),
                ServerProfile("provider-auto", "Автовыбор", "xray",
                    raw = """{"remarks":"Автовыбор","outbounds":[{"protocol":"vless"}]}"""),
            ),
            updatedAt = now,
            announcement = "Условия провайдера", downloadBytes = 42,
            webPageUrl = "https://first.example/account",
        )
        val second = Subscription(
            id = "test-owner-two", title = "Тестовая подписка",
            source = "$endpoint/second",
            servers = listOf(ServerProfile("same-server-id", "Швейцария тест", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000002@b.example:443#CH")),
            updatedAt = now,
        )
        try {
            store.write(listOf(first, second))
            prefs.edit().putString("selected_subscription_id", first.id)
                .putString("selected_server_id", "same-server-id")
                .remove("pinned_sub_id").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Phone Home now owns the complete server LazyColumn; no second scroll.
                compose.awaitVisible(hasContentDescription("Вкладка Главная"))
                compose.onNode(hasScrollAction()).performScrollToNode(hasText("Тестовая подписка · 1"))
                val firstTitle = "${first.title} · 1"
                val secondTitle = "${second.title} · 2"
                compose.awaitVisible(hasText(firstTitle))
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(secondTitle))
                compose.awaitVisible(hasText(secondTitle))
                // Every owner has the SAME visible manual ping control, regardless
                // of title, chosen provider or underlying VLESS/HY2/TUIC scheme.
                val pingFirst = hasContentDescription("Проверить пинг $firstTitle")
                val pingSecond = hasContentDescription("Проверить пинг $secondTitle")
                compose.onNode(hasScrollAction()).performScrollToNode(pingFirst)
                // An automatic ping may already be running, so the control
                // can legitimately be disabled for the duration of that scan.
                // Its presence for EVERY owner is the invariant here.
                compose.awaitVisible(pingFirst)
                // Phone must match tablet/TV: refresh BEFORE manual ping, both
                // in the top action row. Their bounds must never overlap.
                val refreshFirstHeader = hasContentDescription("Обновить подписку $firstTitle")
                val refreshFirstBounds = compose.awaitVisible(refreshFirstHeader)
                    .fetchSemanticsNode().boundsInRoot
                val pingFirstBounds = compose.awaitVisible(pingFirst)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Refresh must precede ping, without overlap",
                    refreshFirstBounds.right <= pingFirstBounds.left)
                compose.onNode(hasScrollAction()).performScrollToNode(pingSecond)
                compose.awaitVisible(pingSecond)
                val refreshSecondHeader = hasContentDescription("Обновить подписку $secondTitle")
                val refreshSecondBounds = compose.awaitVisible(refreshSecondHeader)
                    .fetchSemanticsNode().boundsInRoot
                val pingSecondBounds = compose.awaitVisible(pingSecond)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("All providers must keep the same action order",
                    refreshSecondBounds.right <= pingSecondBounds.left)
                val secondServer = hasContentDescription("Выбрать сервер Швейцария тест")
                compose.onNode(secondServer).assertDoesNotExist()
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Выбрать сервер Нидерланды тест"))
                compose.awaitVisible(hasContentDescription("Выбрать сервер Нидерланды тест"))
                compose.awaitVisible(hasContentDescription("Выбрать сервер Автовыбор"))
                    .performTouchInput { click() }
                compose.runOnIdle {
                    assertEquals(first.id, prefs.getString("selected_subscription_id", null))
                    assertEquals("provider-auto", prefs.getString("selected_server_id", null))
                }
                // Server groups start with the first owner's group open. The
                // second group must be opened before choosing its server.
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Раскрыть подписку $secondTitle"))
                compose.awaitVisible(hasContentDescription("Раскрыть подписку $secondTitle"))
                    .performTouchInput { click() }
                compose.onNode(hasScrollAction()).performScrollToNode(secondServer)
                compose.awaitVisible(secondServer).performTouchInput { click() }
                // Since 0.3.26 the default open group follows the selected owner.
                // The previously selected group is collapsed unless the USER explicitly
                // expands it. Prove user-chosen expansion remains independently editable.
                val expandFirst = hasContentDescription("Раскрыть подписку $firstTitle")
                compose.onNode(hasScrollAction()).performScrollToNode(expandFirst)
                compose.awaitVisible(expandFirst).performTouchInput { click() }
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Выбрать сервер Автовыбор"))
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Свернуть подписку $firstTitle"))
                compose.awaitVisible(hasContentDescription("Свернуть подписку $firstTitle"))
                    .performTouchInput { click() }
                compose.onNode(hasContentDescription("Выбрать сервер Автовыбор"))
                    .assertDoesNotExist()
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Кабинет $firstTitle"))
                compose.awaitVisible(hasContentDescription("Кабинет $firstTitle"))
                compose.onAllNodes(hasText("Трафик"))[0].assertExists()
                compose.onNode(hasScrollAction()).performScrollToNode(secondServer)
                compose.awaitVisible(secondServer)
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Подробнее о $firstTitle"))
                compose.awaitVisible(hasContentDescription("Подробнее о $firstTitle"))
                    .performTouchInput { click() }
                compose.awaitVisible(hasText("Условия провайдера"))
                compose.onNode(hasContentDescription("Выбрать сервер Автовыбор"))
                    .assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(second.id, prefs.getString("selected_subscription_id", null))
                    assertEquals("same-server-id", prefs.getString("selected_server_id", null))
                }

                val refreshFirst = hasContentDescription("Обновить подписку $firstTitle")
                val refreshSecond = hasContentDescription("Обновить подписку $secondTitle")
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Управление подпиской $firstTitle"))
                compose.awaitVisible(hasContentDescription("Управление подпиской $firstTitle"))
                compose.onNode(hasScrollAction()).performScrollToNode(
                    hasContentDescription("Управление подпиской $secondTitle"))
                compose.awaitVisible(hasContentDescription("Управление подпиской $secondTitle"))
                compose.onNode(hasScrollAction()).performScrollToNode(refreshFirst)
                compose.awaitVisible(refreshFirst and isEnabled()).performTouchInput { click() }
                assertEquals("/first", server.takeRequest(8, TimeUnit.SECONDS)?.path)
                compose.awaitVisible(refreshFirst and isNotEnabled())
                compose.onNode(hasScrollAction()).performScrollToNode(refreshSecond)
                compose.awaitVisible(refreshSecond and isEnabled()).performTouchInput { click() }
                assertEquals("/second", server.takeRequest(8, TimeUnit.SECONDS)?.path)
                releaseResponses.countDown()
                compose.awaitVisible(hasText("Ошибка обновления · серверы сохранены"))
                compose.awaitVisible(refreshFirst and isEnabled())
                val subscriptions = store.read()
                assertEquals(2, subscriptions.size)
                assertEquals(refreshedLink, subscriptions.first().servers.single().raw)
                assertEquals(second.copy(selectedServerId = "same-server-id"), subscriptions.last())
                val secondMenu = hasContentDescription("Управление подпиской $secondTitle")
                compose.onNode(hasScrollAction()).performScrollToNode(secondMenu)
                compose.awaitVisible(secondMenu).performClick()
                // No duplicate manual ping in the owner action sheet: the
                // per-owner gauge icon is visible without opening this menu.
                compose.onNode(hasText("Проверить пинг всех серверов")).assertDoesNotExist()
                compose.onNode(hasText("Проверить пинг")).assertDoesNotExist()
                for (action in listOf("Обновить подписку", "Скопировать подписку",
                        "Показать QR-код", "Закрепить", "Поделиться", "Удалить подписку")) {
                    compose.onNode(hasText(action)).assertExists()
                }
                compose.onNode(hasText("Показать QR-код")).performScrollTo().performClick()
                compose.awaitVisible(hasContentDescription("QR-код"))
                compose.awaitVisible(hasText("Закрыть")).performClick()
                compose.awaitVisible(secondMenu).performClick()
                compose.onNode(hasText("Удалить подписку")).performScrollTo().performClick()
                compose.awaitVisible(hasText("Удалить подписку?"))
                compose.awaitVisible(hasText("Отмена")).performClick()
                assertEquals(2, store.read().size)
                compose.awaitVisible(hasContentDescription("Вкладка Подписка"))
                    .performTouchInput { click() }
                compose.awaitVisible(hasText("Купить подписку"))
                compose.onNode(hasText("Купить")).assertDoesNotExist()
                compose.onNode(hasText("Личный кабинет")).assertDoesNotExist()
                compose.onNode(hasText("Поддержка")).assertDoesNotExist()
                compose.onNode(hasText(secondTitle)).assertDoesNotExist()
                assertEquals(first.webPageUrl, subscriptions.first().webPageUrl)
                assertEquals(first.announcement, subscriptions.first().announcement)
                compose.awaitVisible(hasContentDescription("Вкладка Главная"))
                    .performTouchInput { click() }
                // App recreation must not reset ownership to the first
                // subscription even when both servers have the same ID.
                scenario.recreate()
                assertEquals(second.id, prefs.getString("selected_subscription_id", null))
                assertEquals(2, store.read().size)
            }
        } finally {
            releaseResponses.countDown()
            server.close()
            store.write(saved)
            prefs.edit().putString("selected_subscription_id", oldSub)
                .putString("selected_server_id", oldServer)
                .putString("pinned_sub_id", oldPinned).commit()
        }
    }
}
