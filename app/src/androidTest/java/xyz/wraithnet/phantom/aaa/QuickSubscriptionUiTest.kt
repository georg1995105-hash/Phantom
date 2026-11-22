package xyz.wraithnet.phantom.aaa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import xyz.wraithnet.phantom.ui.home.hasQrCamera
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
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
import xyz.wraithnet.phantom.ui.awaitVisible
import xyz.wraithnet.phantom.data.SubscriptionStore

@RunWith(AndroidJUnit4::class)
// Keep this UI-only test ahead of memory-intensive JNI tests when AndroidJUnitRunner
// discovers tests in class-name order. Shared emulator system launcher ANR after JNI
// stress can otherwise prevent ActivityScenario teardown despite passing UI checks.
class AQuickSubscriptionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun homeUnifiedImportAddsClipboardLinkAndRejectsDuplicate() {
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
                val entry = hasContentDescription("Добавить подписку или ключ") and isEnabled()
                compose.awaitVisible(hasContentDescription("Вкладка Главная"))
                compose.onNode(hasScrollAction()).performScrollToNode(entry)
                compose.awaitVisible(entry)
                if (hasQrCamera(context)) {
                    compose.awaitVisible(hasContentDescription("Сканировать QR-код подписки"))
                } else {
                    compose.onNode(hasContentDescription("Сканировать QR-код подписки"))
                        .assertDoesNotExist()
                }
                compose.awaitVisible(entry).performTouchInput { click() }
                compose.awaitVisible(hasText("Добавить конфигурацию"))
                compose.awaitVisible(hasText("Ссылка подписки"))
                compose.awaitVisible(hasText("Ручной ввод"))
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
                    // CI's default launcher (Quickstep) may ANR after heavyweight JNI
                    // integration tests. Recover ONLY that verified system ANR; keep
                    // every Phantom focus / import assertion and fail on app ANRs.
                    val launcherAnr = window.lineSequence().any {
                        it.contains("mCurrentFocus=") &&
                            it.contains("Application Not Responding: com.android.launcher3")
                    }
                    val root = automation.rootInActiveWindow
                    val knownTitle = root?.findAccessibilityNodeInfosByText("Quickstep isn't responding")
                        ?.isNotEmpty() == true
                    val close = root?.findAccessibilityNodeInfosByText("Close app")?.firstOrNull()
                    if (!launcherAnr || !knownTitle ||
                        close?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true
                    ) {
                        throw AssertionError("Android did not focus the clipboard test window:\n$window", error)
                    }
                    // A verified *system launcher* ANR can survive its own Close
                    // button while the activity remains alive in the background.
                    // Force-stop ONLY that launcher process on this disposable CI
                    // emulator. Never suppress an ANR from Phantom itself.
                    ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand("am force-stop com.android.launcher3")
                    ).use { it.readBytes() }
                    context.startActivity(Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    try {
                        compose.waitUntil(20_000) {
                            var focused = false
                            scenario.onActivity { focused = it.hasWindowFocus() }
                            focused
                        }
                    } catch (retry: ComposeTimeoutException) {
                        throw AssertionError("System Quickstep ANR was dismissed, but Phantom still " +
                            "did not regain window focus:\n$window", retry)
                    }
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
                compose.awaitVisible(hasText("Из буфера обмена")).performTouchInput { click() }
                compose.awaitVisible(hasText("Подписка добавлена"))
                assertEquals(1, store.read().size)
                assertEquals("First", store.read().single().servers.single().name)

                compose.awaitVisible(entry).performTouchInput { click() }
                compose.awaitVisible(hasText("Из буфера обмена")).performTouchInput { click() }
                compose.awaitVisible(hasText("Эта подписка уже добавлена"))
                assertEquals(1, store.read().size)
                // Explicit finish avoids ActivityScenario's synthetic DESTROYED transition
                // while a launcher transition may leave an otherwise healthy Activity
                // in PAUSED. Still require real Android onDestroy before passing.
                scenario.onActivity { it.finish() }
                compose.waitUntil(20_000) {
                    scenario.state == Lifecycle.State.DESTROYED
                }
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally {
            store.write(previous)
            prefs.edit().putString("selected_subscription_id", previousSelectedSub)
                .putString("selected_server_id", previousSelectedServer).commit()
            if (oldClip != null) clipboard.setPrimaryClip(oldClip) else clipboard.clearPrimaryClip()
        }
    }
}
