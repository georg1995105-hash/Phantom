package xyz.wraithnet.phantom.ui

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionStore

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
        val first = Subscription(
            id = "test-owner-one", title = "Тестовая подписка 1",
            source = "https://example.com/sub1",
            servers = listOf(ServerProfile("same-server-id", "Нидерланды тест", "vless",
                raw = "vless://00000000-0000-0000-0000-000000000001@a.example:443#NL")),
            updatedAt = now,
        )
        val second = Subscription(
            id = "test-owner-two", title = "Тестовая подписка 2",
            source = "https://example.com/sub2",
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
                assertNotNull("First subscription heading missing",
                    device.wait(Until.findObject(By.text(first.title)), 8_000))
                assertNotNull("Second subscription heading missing",
                    device.wait(Until.findObject(By.text(second.title)), 8_000))
                assertNotNull("First subscription must have independent refresh action",
                    device.findObject(By.desc("Обновить подписку ${first.title}")))
                assertNotNull("Second subscription must have independent refresh action",
                    device.findObject(By.desc("Обновить подписку ${second.title}")))
                assertNull("Inactive subscription's servers must be collapsed",
                    device.findObject(By.text("Швейцария тест")))
                device.findObject(By.desc("Раскрыть подписку ${second.title}")).click()
                assertNotNull(device.wait(Until.findObject(By.text("Швейцария тест")), 8_000))
                device.findObject(By.text("Швейцария тест")).click()
                assertEquals(second.id, prefs.getString("selected_subscription_id", null))
                assertEquals("same-server-id", prefs.getString("selected_server_id", null))
                assertEquals(2, store.read().size)
                // App recreation must not reset ownership to the first
                // subscription even when both servers have the same ID.
                scenario.recreate()
                assertEquals(second.id, prefs.getString("selected_subscription_id", null))
                assertEquals(2, store.read().size)
            }
        } finally {
            store.write(saved)
            prefs.edit().putString("selected_subscription_id", oldSub)
                .putString("selected_server_id", oldServer).commit()
        }
    }
}
