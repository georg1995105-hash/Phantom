package xyz.wraithnet.phantom.data

import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.service.SubscriptionRefreshWorker
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SubscriptionUserAgentTest {
    @Test
    fun installedVersionReachesServerOnImportAndRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val expected = "Phantom/" + requireNotNull(version)
        val store = SubscriptionStore(context)
        val previous = store.read()
        val device = UiDevice.getInstance(instrumentation)
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
                    val refresh = By.desc("Обновить выбранную подписку").enabled(true)
                    requireNotNull(device.wait(Until.findObject(refresh), 10_000)).click()
                    received("manual refresh")
                    requireNotNull(device.wait(Until.findObject(By.text("Подписка обновлена")), 10_000))
                }
                expire()
                ActivityScenario.launch(MainActivity::class.java).use {
                    received("foreground automatic refresh")
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
