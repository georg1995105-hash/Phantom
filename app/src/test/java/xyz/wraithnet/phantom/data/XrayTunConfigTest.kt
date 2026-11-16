package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class XrayTunConfigTest {
    @Test fun shareLinkUsesNamedTunWithoutNetlinkInterfaceEnumeration() {
        val server = ServerProfile(id = "vless", name = "Demo", scheme = "vless",
            raw = "vless://00000000-0000-0000-0000-000000000001@example.org:443?type=xhttp&security=reality&pbk=abc&sid=def")
        val root = JSONObject(XrayConfigBuilder.build(server))
        assertEquals("tun0", root.getJSONArray("inbounds").getJSONObject(0)
            .getJSONObject("settings").getString("name"))
    }

    @Test fun completeRemnawaveJsonAlsoUsesNamedTunAndKeepsOutbounds() {
        val raw = """{"remarks":"Demo","inbounds":[{"tag":"socks","protocol":"socks","port":1080}],
            "outbounds":[{"tag":"proxy","protocol":"freedom"}],
            "routing":{"rules":[{"type":"field","inboundTag":["socks"],"outboundTag":"proxy"}]}}"""
        val root = JSONObject(XrayConfigBuilder.build(
            ServerProfile(id = "json", name = "Demo", scheme = "xray", raw = raw)))
        assertEquals("tun0", root.getJSONArray("inbounds").getJSONObject(0)
            .getJSONObject("settings").getString("name"))
        assertEquals("proxy", root.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        assertEquals("tun", root.getJSONObject("routing").getJSONArray("rules")
            .getJSONObject(0).getJSONArray("inboundTag").getString(0))
    }
    @Test fun outboundStatisticsEnabledForShareLinks() {
        val server = ServerProfile(id = "vless", name = "Demo", scheme = "vless",
            raw = "vless://00000000-0000-0000-0000-000000000001@example.org:443?type=xhttp")
        val root = JSONObject(XrayConfigBuilder.build(server))
        assertTrue(root.has("stats"))
        val system = root.getJSONObject("policy").getJSONObject("system")
        assertTrue(system.getBoolean("statsOutboundUplink"))
        assertTrue(system.getBoolean("statsOutboundDownlink"))
    }

    @Test fun providerPolicyPreservedWhenEnablingOutboundStatistics() {
        val raw = """{"policy":{"levels":{"0":{"handshake":19}},
            "system":{"statsInboundUplink":false}},
            "outbounds":[{"tag":"proxy","protocol":"freedom"}]}"""
        val root = JSONObject(XrayConfigBuilder.build(
            ServerProfile(id = "json", name = "Demo", scheme = "xray", raw = raw)))
        val policy = root.getJSONObject("policy")
        assertEquals(19, policy.getJSONObject("levels").getJSONObject("0").getInt("handshake"))
        assertFalse(policy.getJSONObject("system").getBoolean("statsInboundUplink"))
        assertTrue(policy.getJSONObject("system").getBoolean("statsOutboundDownlink"))
    }

}
