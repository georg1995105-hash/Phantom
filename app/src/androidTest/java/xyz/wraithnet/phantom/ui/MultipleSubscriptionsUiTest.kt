package xyz.wraithnet.phantom.ui

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
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
    @Test fun serverGroupsAndRefreshButtonsKeepSeparateOwnersWithIdenticalServerIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SubscriptionStore(context)
        val saved = store.read()
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val oldSub = prefs.getString("selected_subscription_id", null)
        val oldServer = prefs.getString("selected_server_id", null)
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
            servers = listOf(ServerProfile("same-server-id", "Нидерланды тест", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000001@a.example:443#NL")),
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
                .putString("selected_server_id", "same-server-id").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertNotNull(device.wait(Until.findObject(By.desc("Вкладка Серверы")), 12_000))
                device.findObject(By.desc("Вкладка Серверы")).click()
                val firstTitle = "${first.title} · 1"
                val secondTitle = "${second.title} · 2"
                assertNotNull("First subscription heading missing",
                    device.wait(Until.findObject(By.text(firstTitle)), 8_000))
                assertNotNull("Second subscription heading missing",
                    device.wait(Until.findObject(By.text(secondTitle)), 8_000))
                assertNull("Inactive subscription's servers must be collapsed",
                    device.findObject(By.text("Швейцария тест")))
                device.findObject(By.desc("Выбрать сервер Нидерланды тест")).click()
                device.findObject(By.desc("Раскрыть подписку $secondTitle")).click()
                val secondServer = By.desc("Выбрать сервер Швейцария тест")
                requireNotNull(device.wait(Until.findObject(secondServer), 8_000)).click()
                assertEquals(second.id, prefs.getString("selected_subscription_id", null))
                assertEquals("same-server-id", prefs.getString("selected_server_id", null))

                val refreshFirst = By.desc("Обновить подписку $firstTitle")
                val refreshSecond = By.desc("Обновить подписку $secondTitle")
                requireNotNull(device.findObject(refreshFirst)).click()
                assertEquals("/first", server.takeRequest(8, TimeUnit.SECONDS)?.path)
                assertTrue(device.wait(Until.hasObject(refreshFirst.enabled(false)), 5_000))
                requireNotNull(device.findObject(refreshSecond.enabled(true))).click()
                assertEquals("/second", server.takeRequest(8, TimeUnit.SECONDS)?.path)
                releaseResponses.countDown()
                assertNotNull(device.wait(Until.findObject(
                    By.text("Ошибка обновления · серверы сохранены")), 10_000))
                assertTrue(device.wait(Until.hasObject(By.desc("Обновить подписку $firstTitle")
                    .enabled(true)), 8_000))
                val subscriptions = store.read()
                assertEquals(2, subscriptions.size)
                assertEquals(refreshedLink, subscriptions.first().servers.single().raw)
                assertEquals(second.copy(selectedServerId = "same-server-id"), subscriptions.last())
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
                .putString("selected_server_id", oldServer).commit()
        }
    }
}
