package xyz.wraithnet.phantom.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.data.LatencyProbe
import xyz.wraithnet.phantom.data.SubscriptionParser
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class LatencyProbeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target = "http://127.0.0.1:34301/Latency"

    private fun fixture(name: String): String {
        val assets = instrumentation.context.assets
        assumeTrue("Requires CI peers", "peers.json" in assets.list("").orEmpty())
        return assets.open(name).bufferedReader().use { it.readText() }
    }

    @Test fun measuresActualResponseThroughEachTransportAndTheProviderBalancer() = runBlocking {
        val cases = JSONObject(fixture("peers.json"))
        val profiles = listOf("VLESS-XHTTP-REALITY", "VLESS-XHTTP-H3", "VLESS-TCP-HTTP",
            "hysteria2", "tuic", "anytls", "shadowsocks")
            .map { SubscriptionParser.parse(cases.getString(it), it).let { sub ->
                sub.servers.single().copy(name = it)
            } } + SubscriptionParser.parse(fixture("provider-balance.json")).servers
        val failures = profiles.mapNotNull { profile ->
            val delay = LatencyProbe.measureResult(instrumentation.targetContext, profile, target, 6_000)
            // The runner origin deliberately waits 50 ms before replying. A
            // TCP handshake or a QUIC negotiation cannot satisfy this check.
            Log.i("PhantomLatencyTest", "${profile.name}: $delay ms through the actual profile")
            if ((delay ?: 0L) < 45L) "${profile.name} ($delay)" else null
        }
        assertTrue("No delayed response for ${failures.joinToString()}", failures.isEmpty())
    }

    @Test fun openPortWithInvalidAuthenticationDoesNotProduceAPing() = runBlocking {
        val cases = JSONObject(fixture("peers.json"))
        val profile = SubscriptionParser.parse(cases.getString("VLESS-XHTTP")).servers.single()
        val wrong = profile.copy(raw = profile.raw.replaceFirst(
            Regex("(?<=://)[^@]+"), "00000000-0000-4000-8000-000000000999"
        ))
        assertNull(LatencyProbe.measureResult(instrumentation.targetContext, wrong, target, 2_000))
    }

    @Test fun providerDirectRouteDoesNotPassAsAProxyMeasurement() = runBlocking {
        MockWebServer().use { origin ->
            origin.start(InetAddress.getByName("127.0.0.1"), 0)
            origin.enqueue(MockResponse().setResponseCode(204))
            val profile = SubscriptionParser.parse(
                """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
            ).servers.single()
            val url = "http://127.0.0.1:${origin.port}/direct"
            assertNull(LatencyProbe.measureResult(instrumentation.targetContext, profile, url, 2_000))
            assertEquals(0, origin.requestCount)
        }
    }
}
