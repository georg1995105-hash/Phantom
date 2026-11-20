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
import xyz.wraithnet.phantom.data.TcpEndpoint
import xyz.wraithnet.phantom.data.XrayConfigBuilder
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
            } } + listOf("provider-balance.json", "observed-balance.json").map { name ->
                SubscriptionParser.parse(fixture(name)).servers.single().copy(name = name)
            }
        val failures = profiles.mapNotNull { profile ->
            val delay = LatencyProbe.checkTraffic(instrumentation.targetContext, profile, target, 6_000)
            // The runner origin deliberately waits 50 ms before replying. A
            // TCP handshake or a QUIC negotiation cannot satisfy this check.
            Log.i("PhantomLatencyTest", "${profile.name}: $delay ms through the actual profile")
            if ((delay ?: 0L) < 45L) "${profile.name} ($delay)" else null
        }
        assertTrue("No delayed response for ${failures.joinToString()}", failures.isEmpty())
    }

    @Test fun openPortWithInvalidAuthenticationFailsTrafficCheck() = runBlocking {
        val cases = JSONObject(fixture("peers.json"))
        val profile = SubscriptionParser.parse(cases.getString("VLESS-XHTTP")).servers.single()
        val wrong = profile.copy(raw = profile.raw.replaceFirst(
            Regex("(?<=://)[^@]+"), "00000000-0000-4000-8000-000000000999"
        ))
        assertNull(LatencyProbe.checkTraffic(instrumentation.targetContext, wrong, target, 2_000))
    }

    @Test fun listTcpReturnsForASilentPortWhileTrafficVerificationFails() = runBlocking {
        fixture("peers.json")
        val ctx = instrumentation.targetContext
        val profile = SubscriptionParser.parse(
            "vless://00000000-0000-4000-8000-000000000001@10.0.2.2:34304?type=xhttp#Автовыбор"
        ).servers.single()
        val start = android.os.SystemClock.elapsedRealtime()
        val result = LatencyProbe.measureResult(ctx, profile, LatencyProbe.tcpEndpoint(profile))
        Log.i("PhantomLatencyTest", "TCP connect: $result ms, first result after " +
            "${android.os.SystemClock.elapsedRealtime() - start} ms")
        assertTrue("Bare TCP listener did not yield a handshake", (result ?: 0) > 0)
        assertNull(LatencyProbe.checkTraffic(ctx, profile, target, 900))
    }

    @Test fun onlyTheTcpFallbackThatDeliveredTheResponseCanBeReported() = runBlocking {
        val raw = org.json.JSONArray(fixture("provider-balance.json")).getJSONObject(0)
        val peer = raw.getJSONArray("outbounds").getJSONObject(2)
        val addr = peer.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        addr.put("address", "10.0.2.2")
        peer.getJSONObject("streamSettings").remove("sockopt")
        val profile = SubscriptionParser.parse(raw.toString()).servers.single()
        assertNull(LatencyProbe.tcpEndpoint(profile))
        val timing = ProfileLatencyClient.measure(instrumentation.targetContext,
            XrayConfigBuilder.build(profile), false, target, 6_000)
        assertTrue((timing?.milliseconds ?: 0) >= 45)
        assertEquals(TcpEndpoint("10.0.2.2", addr.getInt("port")), timing?.tcpPeer)
    }

    @Test fun providerDirectRouteDoesNotPassAsAProxyMeasurement() = runBlocking {
        MockWebServer().use { origin ->
            origin.start(InetAddress.getByName("127.0.0.1"), 0)
            origin.enqueue(MockResponse().setResponseCode(204))
            val profile = SubscriptionParser.parse(
                """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
            ).servers.single()
            val url = "http://127.0.0.1:${origin.port}/direct"
            assertNull(LatencyProbe.checkTraffic(instrumentation.targetContext, profile, url, 2_000))
            assertEquals(0, origin.requestCount)
        }
    }
}
