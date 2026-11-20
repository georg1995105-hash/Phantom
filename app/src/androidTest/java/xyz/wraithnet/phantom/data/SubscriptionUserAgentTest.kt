package xyz.wraithnet.phantom.data

import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.service.SubscriptionRefreshWorker
import xyz.wraithnet.phantom.ui.awaitVisible
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SubscriptionUserAgentTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun installedVersionReachesServerOnImportAndRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val expected = "Phantom/" + requireNotNull(version)
        val store = SubscriptionStore(context)
        val previous = store.read()
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val url = "http://127.0.0.1:${server.port}/subscription"
            repeat(4) {
                server.enqueue(MockResponse().setBody(
                    "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443#Test"))
            }
            fun received(scenario: String) {
                val request = requireNotNull(server.takeRequest(20, TimeUnit.SECONDS)) { scenario }
                assertEquals(scenario, "/subscription", request.path)
                assertEquals(scenario, listOf(expected), request.headers.values("User-Agent"))
                Log.i("PhantomUserAgent", "$scenario received $expected")
            }
            fun expire() {
                store.write(listOf(store.read().single().copy(updatedAt = 0L)))
            }
            try {
                store.write(emptyList())
                val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                ActivityScenario.launch<MainActivity>(intent).use {
                    received("import")
                    val refresh = compose.awaitVisible(
                        hasContentDescription("Обновить выбранную подписку") and isEnabled()
                    )
                    val importedAt = store.read().single().updatedAt
                    refresh.performTouchInput { click() }
                    received("manual refresh")
                    compose.awaitVisible(hasText("Подписка обновлена"))
                    assertTrue(store.read().single().updatedAt > importedAt)
                }
                expire()
                ActivityScenario.launch(MainActivity::class.java).use {
                    received("foreground automatic refresh")
                    compose.waitUntil(10_000) { store.read().single().updatedAt > 0L }
                }
                expire()
                val worker = TestListenableWorkerBuilder<SubscriptionRefreshWorker>(context).build()
                assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
                received("background worker")
            } finally {
                store.write(previous)
            }
        }
    }
}
