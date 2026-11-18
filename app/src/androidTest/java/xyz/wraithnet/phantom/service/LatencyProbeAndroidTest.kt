package xyz.wraithnet.phantom.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.NetworkCapabilities
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

    @Test fun physicalTcpDoesNotRequireAndroidInternetValidation() {
        val unvalidated = NetworkCapabilities.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        assertEquals(10, LatencyProbe.physicalNetworkRank(unvalidated))
        val validated = NetworkCapabilities.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        assertEquals(0, LatencyProbe.physicalNetworkRank(validated))
        val vpn = NetworkCapabilities.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        assertNull("A VPN Network must never be selected", LatencyProbe.physicalNetworkRank(vpn))
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
