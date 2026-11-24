package xyz.wraithnet.phantom.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.ui.awaitVisible

@RunWith(AndroidJUnit4::class)
class SubscriptionDeepLinkTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun browserIntentImportsServerAndDuplicateDoesNotCreateAnotherSubscription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SubscriptionStore(context)
        val before = store.read()
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val oldOwner = prefs.getString("selected_subscription_id", null)
        val oldServer = prefs.getString("selected_server_id", null)
        val peer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val source = "http://127.0.0.1:${peer.localPort}/sub?one=1&two=a%2Bb"
        val encoded = URLEncoder.encode(source, "UTF-8")
        val link = Uri.parse("phantom://import?url=$encoded")
        val body = "vless://00000000-0000-0000-0000-000000000001@example.org:443#DeepLink"
        val requests = java.util.concurrent.atomic.AtomicInteger(0)
        val server = thread(isDaemon = true) {
            runCatching {
                while (!peer.isClosed) peer.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    var line = input.readLine()
                    while (!line.isNullOrEmpty()) line = input.readLine()
                    requests.incrementAndGet()
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    output.write(bytes)
                    output.flush()
                }
            }
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, link).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName)
            assertEquals(MainActivity::class.java.name,
                intent.resolveActivity(context.packageManager)?.className)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 20000
                while (store.read().none { it.source == source } &&
                    SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                val added = store.read().filter { it.source == source }
                assertEquals(1, added.size)
                assertEquals(body, added.single().servers.single().raw)
                assertEquals(1, requests.get())
                // Re-deliver the URI from the existing task. Verify that the
                // duplicate was actually processed, not merely that no request
                // appeared during a fixed delay.
                scenario.onActivity { it.startActivity(Intent(intent)) }
                compose.awaitVisible(hasText("Эта подписка уже добавлена"))
                assertEquals(1, store.read().count { it.source == source })
                assertEquals(1, requests.get())
                // singleTask may replace the Activity tracked by ActivityScenario.
                // Finish the actual live Activity, not a stale scenario reference.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED)
                        .flatMap { monitor.getActivitiesInStage(it).toList() }
                        .filterIsInstance<MainActivity>().distinct()
                        .forEach { if (!it.isFinishing && !it.isDestroyed) it.finish() }
                }
            }
        } finally {
            peer.close()
            server.join(2000)
            store.write(before)
            prefs.edit().putString("selected_subscription_id", oldOwner)
                .putString("selected_server_id", oldServer).commit()
        }
    }
}
