package xyz.wraithnet.phantom.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.data.LatencyProbe
import xyz.wraithnet.phantom.data.ServerProfile
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class LatencyProbeAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun balancedTcpJsonDoesNotAssignItsFirstPeersPingToTheWholeProfile() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val proxy = JSONObject("""{"protocol":"vless","settings":{"vnext":[
                {"address":"127.0.0.1","port":${listener.localPort}}]}}""")
            val profile = ServerProfile("auto", "Автовыбор", "xray", raw = JSONObject()
                .put("outbounds", org.json.JSONArray().put(proxy).put(proxy)).toString())
            assertNull(LatencyProbe.measureResult(context, profile))
            val single = profile.copy(raw = JSONObject().put("outbounds", org.json.JSONArray().put(proxy)).toString())
            assertEquals("TCP", LatencyProbe.measureResult(context, single)?.source)
        }
    }

    @Test fun physicalTcpDoesNotRequireAndroidInternetValidation() {
        assertEquals(10, LatencyProbe.physicalNetworkRank(
            notVpn = true, internet = true, validated = false,
            wifi = true, cellular = false, ethernet = false,
        ))
        assertEquals(0, LatencyProbe.physicalNetworkRank(
            notVpn = true, internet = true, validated = true,
            wifi = true, cellular = false, ethernet = false,
        ))
        assertNull("A VPN Network must never be selected", LatencyProbe.physicalNetworkRank(
            notVpn = false, internet = true, validated = false,
            wifi = false, cellular = false, ethernet = false,
        ))
        assertNull("A network without internet must be excluded", LatencyProbe.physicalNetworkRank(
            notVpn = true, internet = false, validated = true,
            wifi = true, cellular = false, ethernet = false,
        ))
    }

    @Test fun reachableTcpProfileHasExplicitTcpSourceWithoutChangingVpn() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val profile = ServerProfile("tcp", "Local TCP", "vless", raw =
                "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:${listener.localPort}#Local")
            assertFalse(LatencyProbe.needsTunnel(profile))
            val result = LatencyProbe.measureResult(context, profile)
            assertNotNull("Local TCP connection was not measured", result)
            assertEquals("TCP", result?.source)
            assertTrue((result?.milliseconds ?: 0L) > 0L)
        }
    }

    @Test fun disconnectedQuicPeersReportTheirOwnUdpRoundTrip() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        org.junit.Assume.assumeTrue("Requires CI peers", "peers.json" in assets.list("").orEmpty())
        val cases = JSONObject(assets.open("peers.json").bufferedReader().use { it.readText() })
        for (name in listOf("tuic", "hysteria2", "VLESS-XHTTP-H3")) {
            val profile = xyz.wraithnet.phantom.data.SubscriptionParser.parse(cases.getString(name)).servers.single()
            val result = LatencyProbe.measureResult(context, profile)
            assertEquals(name, "QUIC", result?.source)
            assertTrue(name, (result?.milliseconds ?: 0L) > 0L)
        }
    }

    @Test fun xrayJsonHysteria2AndXhttpH3DoNotInventTcpPing() = runBlocking {
        val hysteria = ServerProfile("hysteria", "Hysteria2 JSON", "xray", raw = JSONObject()
            .put("outbounds", org.json.JSONArray().put(JSONObject()
                .put("protocol", "hysteria")
                .put("settings", JSONObject().put("version", 2)
                    .put("address", "198.51.100.1").put("port", 443))
                .put("streamSettings", JSONObject().put("network", "hysteria"))))
            .toString())
        val h3 = ServerProfile("h3", "XHTTP HTTP/3", "xray",
            raw = """{"outbounds":[{"protocol":"vless","streamSettings":{
                "network":"xhttp","tlsSettings":{"alpn":["h3"]}}}]}""")
        assertTrue(LatencyProbe.needsTunnel(hysteria))
        assertTrue(LatencyProbe.needsTunnel(h3))
        assertNull("Hysteria2 must not fall back to TCP", LatencyProbe.measureResult(context, hysteria))
        assertNull("HTTP/3 must not fall back to TCP", LatencyProbe.measureResult(context, h3))
    }
}
