package xyz.wraithnet.phantom.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import xyz.wraithnet.phantom.data.SubscriptionStore

@RunWith(AndroidJUnit4::class)
class QuickSubscriptionUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun firstLaunchClipboardAddsSubscriptionWithOneTapAndRejectsDuplicate() {
        val store = SubscriptionStore(context)
        val previous = store.read()
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val previousSelectedSub = prefs.getString("selected_subscription_id", null)
        val previousSelectedServer = prefs.getString("selected_server_id", null)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = clipboard.primaryClip
        val link = "vless://00000000-0000-0000-0000-000000000001@vpn.example.org:443?type=xhttp&security=reality#First"
        try {
            store.write(emptyList())
            prefs.edit().remove("selected_subscription_id").remove("selected_server_id").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Phantom test", link))
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val quickButton = device.wait(Until.findObject(By.text("Добавить подписку")), 12_000)
                assertNotNull("The first screen must show the import shortcut", quickButton)
                quickButton!!.click()
                device.wait(Until.hasObject(By.text("Подписка добавлена")), 8_000)
                assertEquals(1, store.read().size)
                assertEquals("First", store.read().single().servers.single().name)

                quickButton.click()
                assertTrue("Duplicate alert missing",
                    device.wait(Until.hasObject(By.text("Эта подписка уже добавлена")), 6_000))
                assertEquals(1, store.read().size)
            }
        } finally {
            store.write(previous)
            prefs.edit().putString("selected_subscription_id", previousSelectedSub)
                .putString("selected_server_id", previousSelectedServer).commit()
            if (oldClip != null) clipboard.setPrimaryClip(oldClip) else clipboard.clearPrimaryClip()
        }
    }
}
