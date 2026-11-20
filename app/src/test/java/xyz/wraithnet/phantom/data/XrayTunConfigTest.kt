package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class XrayTunConfigTest {
    @Test fun singBoxBridgeUsesCurrentXraySocksOutboundSchema() {
        val port = 39651
        val root = JSONObject(XrayConfigBuilder.buildSocksBridge(port))
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", outbound.getString("protocol"))
        val settings = outbound.getJSONObject("settings")
        assertEquals("127.0.0.1", settings.getString("address"))
        assertEquals(port, settings.getInt("port"))
        assertFalse("Old servers array is not valid in current Xray SOCKS outbound",
            settings.has("servers"))
        assertEquals("tun", root.getJSONArray("inbounds").getJSONObject(0)
            .getString("protocol"))
    }

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

    @Test fun providerServiceRoutesDoNotCaptureAndroidTraffic() {
        val raw = """{"inbounds":[{"tag":"monitor","protocol":"dokodemo-door"},
            {"tag":"socks","protocol":"socks"},{"tag":"http","protocol":"http"}],
            "outbounds":[{"tag":"proxy-a","protocol":"vless"},{"tag":"proxy-b","protocol":"vless"}],
            "routing":{"balancers":[{"tag":"auto","selector":["proxy-"]}],"rules":[
                {"type":"field","inboundTag":["monitor"],"outboundTag":"api"},
                {"type":"field","inboundTag":["socks","http"],"balancerTag":"auto"}]}}"""
        val profile = SubscriptionParser.parse(raw).servers.single()
        val routing = JSONObject(XrayConfigBuilder.build(profile)).getJSONObject("routing")
        val rules = routing.getJSONArray("rules")
        assertEquals("[\"monitor\"]", rules.getJSONObject(0).getJSONArray("inboundTag").toString())
        assertEquals("[\"tun\"]", rules.getJSONObject(1).getJSONArray("inboundTag").toString())
        assertEquals("auto", rules.getJSONObject(1).getString("balancerTag"))
        assertEquals("proxy-", routing.getJSONArray("balancers").getJSONObject(0)
            .getJSONArray("selector").getString(0))
    }

    @Test fun providerSocksSniffingAndObservatorySurviveTunAdaptation() {
        val raw = """{"inbounds":[
            {"tag":"http","protocol":"http","sniffing":{"enabled":true,"destOverride":["http","tls"]}},
            {"tag":"socks","protocol":"socks","sniffing":{"enabled":true,"routeOnly":true,
                "destOverride":["http","tls","quic"]}}],
            "outbounds":[{"tag":"proxy","protocol":"vless"}],
            "burstObservatory":{"subjectSelector":["proxy"],"pingConfig":{"sampling":1,"timeout":"3s"}},
            "routing":{"balancers":[{"tag":"auto","selector":["proxy"],"fallbackTag":"proxy",
                "strategy":{"type":"leastLoad"}}]}}"""
        val config = JSONObject(XrayConfigBuilder.build(SubscriptionParser.parse(raw).servers.single()))
        val sniffing = config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing")
        assertTrue(sniffing.getBoolean("routeOnly"))
        assertEquals("quic", sniffing.getJSONArray("destOverride").getString(2))
        assertEquals("3s", config.getJSONObject("burstObservatory").getJSONObject("pingConfig").getString("timeout"))
        val balancer = config.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0)
        assertEquals("leastLoad", balancer.getJSONObject("strategy").getString("type"))
        assertEquals("proxy", balancer.getString("fallbackTag"))
    }

    @Test fun latencyProxyRequiresAuthenticationAndKeepsTheProvidersBalancer() {
        val full = """{"inbounds":[{"tag":"socks","protocol":"socks"}],
            "outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"proxy","protocol":"vless"}],
            "routing":{"balancers":[{"tag":"auto","selector":["proxy"]}],
                "rules":[{"type":"field","inboundTag":["socks"],"balancerTag":"auto"}]}}"""
        val original = XrayConfigBuilder.build(SubscriptionParser.parse(full).servers.single())
        val config = JSONObject(XrayConfigBuilder.forLatency(original, 12345, "probe-password"))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", inbound.getString("tag"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("http", inbound.getString("protocol"))
        assertEquals("probe-password", inbound.getJSONObject("settings")
            .getJSONArray("accounts").getJSONObject(0).getString("pass"))
        assertEquals("blackhole", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertEquals(JSONObject(original).getJSONObject("routing").toString(),
            config.getJSONObject("routing").toString())
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

    @Test fun xhttpHttp3HasQuicAlpnAndValidNetwork() {
        val profile = ServerProfile(id = "h3", name = "HTTP3", scheme = "vless",
            raw = "vless://00000000-0000-0000-0000-000000000001@example.org:443?type=xhttp&security=tls&alpn=h3&path=%2Fvpn")
        val outbound = JSONObject(XrayConfigBuilder.build(profile)).getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertEquals("h3", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        assertEquals("/vpn", stream.getJSONObject("xhttpSettings").getString("path"))
    }

    @Test fun oldQuicDoesNotProduceBrokenXrayConfig() {
        val profile = ServerProfile(id = "quic", name = "Old", scheme = "vless",
            raw = "vless://00000000-0000-0000-0000-000000000001@example.org:443?type=quic")
        val err = assertThrows(IllegalArgumentException::class.java) {
            XrayConfigBuilder.build(profile)
        }
        assertTrue(err.message.orEmpty().contains("удалён"))
    }

    @Test fun masqueProviderJsonIsPreservedWithoutClaimingLiveCompatibility() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"vless",
            "streamSettings":{"network":"masque","security":"tls"}}]}"""
        val root = JSONObject(XrayConfigBuilder.build(
            ServerProfile(id = "masque", name = "MASQUE", scheme = "xray", raw = raw)))
        assertEquals("masque", root.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getString("network"))
    }

}
