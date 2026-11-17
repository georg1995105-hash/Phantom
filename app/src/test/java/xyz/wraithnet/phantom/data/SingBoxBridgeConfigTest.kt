package xyz.wraithnet.phantom.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SingBoxBridgeConfigTest {
    private val tuic = "tuic://0e30184e-81b9-4ac0-9e43-e47b9c6ad0aa:abc%2Bdef%40ghi@" +
        "solar.veor.pro:25443?congestion_control=cubic&alpn=h3&sni=solar.veor.pro&allow_insecure=0#SE"

    @Test fun tuicLinkImportPersistsProfile() {
        val result = SubscriptionParser.parse(tuic)
        assertEquals(1, result.servers.size)
        assertEquals("tuic", result.servers.single().scheme)
        assertEquals("SE", result.servers.single().name)
    }

    @Test fun realTuicConfigHasValidTLSAndNativeUdpParameters() {
        val config = JSONObject(SingBoxBridgeConfig.fromShareLink(tuic, 39121))
        val local = config.getJSONArray("inbounds").getJSONObject(0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", local.getString("type"))
        assertEquals("127.0.0.1", local.getString("listen"))
        assertEquals(39121, local.getInt("listen_port"))
        assertEquals("tuic", outbound.getString("type"))
        assertEquals("solar.veor.pro", outbound.getString("server"))
        assertEquals(25443, outbound.getInt("server_port"))
        assertEquals("abc+def@ghi", outbound.getString("password"))
        assertEquals("h3", outbound.getJSONObject("tls").getJSONArray("alpn").getString(0))
        assertFalse(outbound.getJSONObject("tls").getBoolean("insecure"))
    }

    @Test fun realAnyTLSConfigIsSupported() {
        val json = JSONObject(SingBoxBridgeConfig.fromShareLink(
            "anytls://my%40password@example.org:443?sni=edge.example.org", 39821))
        val outbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("anytls", outbound.getString("type"))
        assertEquals("my@password", outbound.getString("password"))
        assertEquals("edge.example.org",
            outbound.getJSONObject("tls").getString("server_name"))
    }

    @Test fun bridgeRoutesAllTcpUdpViaNativeSocks5() {
        val root = JSONObject(XrayConfigBuilder.buildSocksBridge(39918))
        val proxy = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", proxy.getString("protocol"))
        assertEquals("127.0.0.1", proxy.getJSONObject("settings").getString("address"))
        assertEquals(39918, proxy.getJSONObject("settings").getInt("port"))
        assertFalse(proxy.getJSONObject("settings").has("servers"))
        assertEquals("tcp,udp", root.getJSONObject("routing")
            .getJSONArray("rules").getJSONObject(0).getString("network"))
    }

    @Test fun rejectsMissingCredentialsAndInvalidHost() {
        for (link in listOf(
            "tuic://user@example.org:443",
            "tuic://invalid:password@example.org:443",
            "tuic://00000000-0000-0000-0000-000000000001:secret@example.org:0",
            "anytls://@example.org:443"
        )) {
            assertThrows(Exception::class.java) {
                SingBoxBridgeConfig.fromShareLink(link, 38001)
            }
        }
    }

    @Test fun mixedSubscriptionDoesNotDiscardTuic() {
        val links = tuic + "\n" + "vless://00000000-0000-0000-0000-000000000001@v.example.org:443?type=xhttp#VLESS"
        val result = SubscriptionParser.parse(links)
        assertEquals(2, result.servers.size)
        assertTrue(result.servers.any { it.scheme == "tuic" })
        assertTrue(result.servers.any { it.scheme == "vless" })
    }
}
