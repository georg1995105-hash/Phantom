package xyz.wraithnet.phantom.data

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import xyz.wraithnet.phantom.service.PhantomVpnService
import xyz.wraithnet.phantom.service.VpnRuntimeBridge

/**
 * The app's UID normally uses physical networking. Prove that a manual
 * request is actually sent to its authenticated loopback proxy rather than
 * silently reaching the subscription endpoint directly.
 */
@RunWith(AndroidJUnit4::class)
class ActiveVpnRefreshNetworkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun explicitActiveCountryUsesAuthenticatedProxyRatherThanDirectInternet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val permission = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} ACTIVATE_VPN allow")
        ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        assertNull("VPN permission missing", VpnService.prepare(context))

        MockWebServer().use { selected ->
            MockWebServer().use { destination ->
                val loopback = InetAddress.getByName("127.0.0.1")
                selected.start(loopback, 0)
                destination.start(loopback, 0)
                selected.enqueue(MockResponse().setBody("through active profile"))
                destination.enqueue(MockResponse().setBody("direct network: wrong"))
                val url = destination.url("/subscription").toString()
                // Real isolated VpnService and Xray route. Only requests to the
                // synthetic subscription port are redirected to the selected
                // endpoint; direct requests reach a separate trap server.
                val profile = """
                    {"outbounds":[
                        {"tag":"deny","protocol":"blackhole"},
                        {"tag":"selected","protocol":"freedom",
                         "settings":{"redirect":"127.0.0.1:${selected.port}"}}
                    ],"routing":{"rules":[
                        {"type":"field","network":"tcp","port":"${destination.port}",
                         "outboundTag":"selected"},
                        {"type":"field","network":"tcp,udp","outboundTag":"deny"}
                    ]}}
                """.trimIndent()
                fun request(route: ActiveVpnSubscriptionProxy.Route) = runBlocking {
                    PhantomHttp.get(context, url, vpnRoute = route,
                        manual = true, callTimeoutMs = 3_000L) { it.body!!.string() }
                }
                fun assertRejected(route: ActiveVpnSubscriptionProxy.Route) {
                    try {
                        request(route)
                        fail("Unowned VPN route must be rejected")
                    } catch (expected: IOException) {
                        assertEquals("Выбранная нода VPN отключена или изменена", expected.message)
                    }
                    assertEquals(0, destination.requestCount)
                }
                context.startForegroundService(Intent(context, PhantomVpnService::class.java)
                    .setAction(PhantomVpnService.ACTION_START)
                    .putExtra(PhantomVpnService.EXTRA_PROFILE, profile))
                try {
                    val deadline = SystemClock.elapsedRealtime() + 15_000L
                    var route = ActiveVpnSubscriptionProxy.forProfile(context, profile)
                    while (route == null && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(100)
                        route = ActiveVpnSubscriptionProxy.forProfile(context, profile)
                    }
                    val owned = requireNotNull(route) {
                        "VPN process did not publish its authenticated route"
                    }
                    assertTrue(VpnRuntimeBridge.isActive(context, owned))
                    assertNull(ActiveVpnSubscriptionProxy.forProfile(context, "vless://other"))
                    // UI-process credentials must never override the IPC owner.
                    ActiveVpnSubscriptionProxy.publish(destination.port, "fake", profile)
                    val forged = requireNotNull(ActiveVpnSubscriptionProxy.forProfile(profile))
                    assertRejected(forged)
                    assertRejected(owned.copy(password = "wrong"))
                    assertEquals(0, selected.requestCount)
                    assertEquals("through active profile", request(owned))
                    assertEquals(0, destination.requestCount)
                    assertEquals(1, selected.requestCount)
                    assertEquals("/subscription", selected.takeRequest(1, TimeUnit.SECONDS)?.path)

                    // Deterministically revoke the session after the response
                    // has arrived, but before the downloader accepts its body.
                    selected.enqueue(MockResponse().setBody("stale response"))
                    try {
                        runBlocking {
                            PhantomHttp.get(context, url, vpnRoute = owned,
                                manual = true, callTimeoutMs = 3_000L) { response ->
                                val body = response.body!!.string()
                                assertEquals("stale response", body)
                                context.startService(Intent(context, PhantomVpnService::class.java)
                                    .setAction(PhantomVpnService.ACTION_STOP))
                                val stopDeadline = SystemClock.elapsedRealtime() + 10_000L
                                while (VpnRuntimeBridge.isActive(context, owned) &&
                                    SystemClock.elapsedRealtime() < stopDeadline) Thread.sleep(100)
                                assertFalse("STOP did not revoke proxy credentials",
                                    VpnRuntimeBridge.isActive(context, owned))
                                body
                            }
                        }
                        fail("Response from a revoked route must not be accepted")
                    } catch (expected: IOException) {
                        assertEquals("Выбранная нода VPN отключена или изменена", expected.message)
                    }
                    assertRejected(owned)
                    assertEquals(2, selected.requestCount)
                    assertEquals(0, destination.requestCount)
                } finally {
                    ActiveVpnSubscriptionProxy.clear()
                    context.startService(Intent(context, PhantomVpnService::class.java)
                        .setAction(PhantomVpnService.ACTION_STOP))
                }
            }
        }
    }

    @Test fun manualTimeoutStopsAfterOneRequestInsteadOfWaitingForMultipleRetries() {
        MockWebServer().use { destination ->
            destination.start(InetAddress.getByName("127.0.0.1"), 0)
            destination.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS)
                .setBody("eventually responds"))
            try {
                runBlocking {
                    PhantomHttp.get(
                        context, destination.url("/slow").toString(),
                        manual = true, callTimeoutMs = 700L,
                    ) { response -> response.body!!.string() }
                }
                fail("Timed-out manual request must not succeed")
            } catch (expected: IOException) {
                assertEquals(1, destination.requestCount)
            }
        }
    }
}
