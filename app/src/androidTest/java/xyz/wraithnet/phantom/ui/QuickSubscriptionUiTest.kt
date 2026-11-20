package xyz.wraithnet.phantom.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.SubscriptionStore

@RunWith(AndroidJUnit4::class)
class QuickSubscriptionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

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
                val shortcut = hasContentDescription("Добавить подписку") and isEnabled()
                val add = compose.awaitVisible(shortcut).getUnclippedBoundsInRoot()
                val tabs = compose.awaitVisible(hasContentDescription("Вкладка Главная"))
                    .getUnclippedBoundsInRoot()
                assertTrue(add.bottom <= tabs.top)
                compose.onNode(hasText("PING")).assertDoesNotExist()
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    val qr = compose.awaitVisible(hasContentDescription("Сканировать QR-код подписки"))
                        .getUnclippedBoundsInRoot()
                    assertTrue(add.right <= qr.left)
                    assertTrue(qr.top < add.bottom && add.top < qr.bottom)
                }
                try {
                    compose.waitUntil(10_000) {
                        var focused = false
                        scenario.onActivity { focused = it.hasWindowFocus() }
                        focused
                    }
                } catch (error: ComposeTimeoutException) {
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    val window = ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand("dumpsys window displays")
                    ).bufferedReader().useLines { lines ->
                        lines.filter {
                            it.contains("mCurrentFocus") || it.contains("mFocusedApp") || it.contains("mScreenOn")
                        }.joinToString("\n")
                    }
                    throw AssertionError("Android did not focus the clipboard test window:\n$window", error)
                }
                scenario.onActivity {
                    val clip = ClipData.newPlainText("Phantom test", link)
                    // The emulator copies on behalf of an external source without showing
                    // the floating SystemUI preview over the app under test.
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true)
                    }
                    clipboard.setPrimaryClip(clip)
                    assertEquals(link, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                }
                compose.awaitVisible(shortcut).performTouchInput { click() }
                val confirmation = compose.awaitVisible(hasText("Подписка добавлена"))
                    .getUnclippedBoundsInRoot()
                assertTrue(confirmation.bottom <= compose.awaitVisible(shortcut).getUnclippedBoundsInRoot().top)
                assertEquals(1, store.read().size)
                assertEquals("First", store.read().single().servers.single().name)

                compose.awaitVisible(shortcut).performTouchInput { click() }
                compose.awaitVisible(hasText("Эта подписка уже добавлена"))
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
