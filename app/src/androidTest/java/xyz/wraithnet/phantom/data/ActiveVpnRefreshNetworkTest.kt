package xyz.wraithnet.phantom.data

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

/**
 * The app's UID normally uses physical networking. Prove that a manual
 * request is actually sent to its authenticated loopback proxy rather than
 * silently reaching the subscription endpoint directly.
 */
@RunWith(AndroidJUnit4::class)
class ActiveVpnRefreshNetworkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun explicitActiveCountryUsesAuthenticatedProxyRatherThanDirectInternet() {
        val proxy = MockWebServer()
        val destination = MockWebServer()
        try {
            proxy.start(InetAddress.getByName("127.0.0.1"), 0)
            destination.start(InetAddress.getByName("127.0.0.1"), 0)
            proxy.enqueue(MockResponse().setResponseCode(407)
                .setHeader("Proxy-Authenticate", "Basic realm=\"phantom\""))
            proxy.enqueue(MockResponse().setBody("through active profile"))
            destination.enqueue(MockResponse().setBody("direct network: wrong"))

            ActiveVpnSubscriptionProxy.publish(proxy.port, "secret-for-test", "vless://selected")
            val route = requireNotNull(ActiveVpnSubscriptionProxy.forProfile("vless://selected"))
            val body = runBlocking {
                PhantomHttp.get(
                    context, destination.url("/subscription").toString(),
                    vpnRoute = route, manual = true, callTimeoutMs = 3_000L,
                ) { response -> response.body!!.string() }
            }
            assertEquals("through active profile", body)
            assertEquals(0, destination.requestCount)
            assertEquals(2, proxy.requestCount)
            proxy.takeRequest(1, TimeUnit.SECONDS)
            val authenticated = requireNotNull(proxy.takeRequest(1, TimeUnit.SECONDS))
            assertTrue(authenticated.getHeader("Proxy-Authorization").orEmpty().startsWith("Basic "))
            assertTrue(authenticated.requestLine.contains("/subscription"))
        } finally {
            ActiveVpnSubscriptionProxy.clear()
            proxy.shutdown()
            destination.shutdown()
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
