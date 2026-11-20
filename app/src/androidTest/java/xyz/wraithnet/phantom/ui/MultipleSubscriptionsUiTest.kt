package xyz.wraithnet.phantom.ui

import android.content.Context
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
                compose.awaitVisible(hasContentDescription("Вкладка Серверы")).performTouchInput { click() }
                val firstTitle = "${first.title} · 1"
                val secondTitle = "${second.title} · 2"
                compose.awaitVisible(hasText(firstTitle))
                compose.awaitVisible(hasText(secondTitle))
                val secondServer = hasContentDescription("Выбрать сервер Швейцария тест")
                compose.onNode(secondServer).assertDoesNotExist()
                compose.awaitVisible(hasContentDescription("Выбрать сервер Нидерланды тест"))
                compose.awaitVisible(hasContentDescription("Выбрать сервер Автовыбор"))
                    .performTouchInput { click() }
                compose.runOnIdle {
                    assertEquals(first.id, prefs.getString("selected_subscription_id", null))
                    assertEquals("provider-auto", prefs.getString("selected_server_id", null))
                }
                // Server groups start with the first owner's group open. The
                // second group must be opened before choosing its server.
                compose.awaitVisible(hasContentDescription("Раскрыть подписку $secondTitle"))
                    .performTouchInput { click() }
                compose.awaitVisible(secondServer).performTouchInput { click() }
                compose.onNode(hasText("Автовыбор")).assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(second.id, prefs.getString("selected_subscription_id", null))
                    assertEquals("same-server-id", prefs.getString("selected_server_id", null))
                }

                val refreshFirst = hasContentDescription("Обновить подписку $firstTitle")
                val refreshSecond = hasContentDescription("Обновить подписку $secondTitle")
                compose.awaitVisible(hasContentDescription("Проверить задержку подписки $firstTitle"))
                compose.awaitVisible(hasContentDescription("Проверить задержку подписки $secondTitle"))
                compose.awaitVisible(refreshFirst and isEnabled()).performTouchInput { click() }
                assertEquals("/first", server.takeRequest(8, TimeUnit.SECONDS)?.path)
                compose.awaitVisible(refreshFirst and isNotEnabled())
                compose.awaitVisible(refreshSecond and isEnabled()).performTouchInput { click() }
                assertEquals("/second", server.takeRequest(8, TimeUnit.SECONDS)?.path)
                releaseResponses.countDown()
                compose.awaitVisible(hasText("Ошибка обновления · серверы сохранены"))
                compose.awaitVisible(refreshFirst and isEnabled())
                val subscriptions = store.read()
                assertEquals(2, subscriptions.size)
                assertEquals(refreshedLink, subscriptions.first().servers.single().raw)
                assertEquals(second.copy(selectedServerId = "same-server-id"), subscriptions.last())
                compose.awaitVisible(hasContentDescription("Вкладка Подписка"))
                    .performTouchInput { click() }
                compose.onNode(hasText("Выбрать подписку")).assertDoesNotExist()
                compose.onNode(hasText("Мои подписки и ключи")).assertDoesNotExist()
                compose.awaitVisible(hasContentDescription("Обновить подписку $firstTitle"))
                compose.awaitVisible(hasContentDescription("Обновить подписку $secondTitle"))
                compose.awaitVisible(hasContentDescription("Управление подпиской $secondTitle"))
                // On the details tab the selected owner's card starts expanded.
                compose.awaitVisible(hasContentDescription("Свернуть подписку $secondTitle"))
                    .performTouchInput { click() }
                compose.awaitVisible(hasContentDescription("Раскрыть подписку $secondTitle"))
                compose.awaitVisible(hasContentDescription("Вкладка Серверы"))
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
