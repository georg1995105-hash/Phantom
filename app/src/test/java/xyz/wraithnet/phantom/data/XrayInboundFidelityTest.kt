package xyz.wraithnet.phantom.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XrayInboundFidelityTest {
    private fun adapt(raw: String) = JSONObject(XrayConfigBuilder.buildJson(raw))

    @Test fun explicitTunDoesNotInheritSocksOrHttpRoutes() {
        val raw = """{
          "inbounds":[{"tag":"local-socks","protocol":"socks"},
            {"tag":"android-entry","protocol":"tun","settings":{"userLevel":8}},
            {"tag":"local-http","protocol":"http"}],
          "outbounds":[{"tag":"nl-primary","protocol":"vless","proxySettings":{"tag":"fi-entry"}},
            {"tag":"fi-entry","protocol":"vless"},{"tag":"nl-reserve","protocol":"vless"},
            {"tag":"block","protocol":"blackhole"}],
          "routing":{"rules":[{"type":"field","inboundTag":["local-socks"],"outboundTag":"block"},
            {"type":"field","inboundTag":["android-entry"],"balancerTag":"nl"},
            {"type":"field","inboundTag":["local-http"],"outboundTag":"fi-entry"}],
            "balancers":[{"tag":"nl","selector":["nl-"],"fallbackTag":"nl-reserve"}]},
          "observatory":{"subjectSelector":["nl-"],"probeUrl":"https://example.org"},
          "dns":{"tag":"resolver","servers":["1.1.1.1"]}
        }"""
        val source = JSONObject(raw)
        val built = adapt(raw)
        assertEquals(1, built.getJSONArray("inbounds").length())
        val tun = built.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("android-entry", tun.getString("tag"))
        assertEquals(8, tun.getJSONObject("settings").getInt("userLevel"))
        for (key in listOf("routing", "outbounds", "observatory", "dns")) {
            assertEquals(source.get(key).toString(), built.get(key).toString())
        }
        val withDns = JSONObject(requireNotNull(XrayConfigBuilder.withProviderTunDns(built.toString())))
        assertEquals("android-entry", withDns.getJSONObject("routing").getJSONArray("rules")
            .getJSONObject(0).getJSONArray("inboundTag").getString(0))
        val withProbe = JSONObject(XrayConfigBuilder.withConnectivityCheck(built.toString(), 12345, "test"))
        val rules = withProbe.getJSONObject("routing").getJSONArray("rules")
        assertEquals(1, rules.getJSONObject(0).getJSONArray("inboundTag").length())
        assertEquals("phantom-check-test", rules.getJSONObject(1).getJSONArray("inboundTag").getString(1))
        assertEquals(1, rules.getJSONObject(2).getJSONArray("inboundTag").length())
    }

    @Test fun distinctProxyPoliciesRequireExplicitTun() {
        val raw = """{"inbounds":[{"tag":"socks","protocol":"socks"},{"tag":"http","protocol":"http"}],
          "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"block","protocol":"blackhole"}],
          "routing":{"rules":[{"type":"field","inboundTag":["socks"],"outboundTag":"block"}]}}"""
        val failure = runCatching { adapt(raw) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun equivalentProxyTagsKeepOriginalRuleAndBalancer() {
        val raw = """{"inbounds":[{"tag":"socks","protocol":"socks"},{"tag":"http","protocol":"http"}],
          "outbounds":[{"tag":"nl","protocol":"vless"}],
          "routing":{"rules":[{"type":"field","inboundTag":["socks","http"],"balancerTag":"exit"}],
            "balancers":[{"tag":"exit","selector":["nl"]}]}}"""
        val built = adapt(raw)
        assertEquals("socks", built.getJSONArray("inbounds").getJSONObject(0).getString("tag"))
        assertEquals(JSONObject(raw).getJSONObject("routing").toString(), built.getJSONObject("routing").toString())
    }

    @Test fun unnamedProxyDoesNotCaptureExistingDnsTunTag() {
        val raw = """{"inbounds":[{"protocol":"socks"}],"outbounds":[{"tag":"proxy","protocol":"vless"}],
          "dns":{"tag":"tun","servers":["1.1.1.1"]},
          "routing":{"rules":[{"type":"field","inboundTag":["tun"],"outboundTag":"proxy"}]}}"""
        val built = adapt(raw)
        assertNotEquals("tun", built.getJSONArray("inbounds").getJSONObject(0).getString("tag"))
        assertEquals(JSONObject(raw).getJSONObject("routing").toString(), built.getJSONObject("routing").toString())
    }
    @Test fun providerHysteriaTransportRemainsUnmodified() {
        val raw = """{"outbounds":[{"tag":"hy2","protocol":"hysteria",
          "streamSettings":{"network":"hysteria","security":"tls",
            "finalmask":{"quicParams":{"udpIdleTimeout":60}}}}]}"""
        assertEquals(JSONObject(raw).getJSONArray("outbounds").toString(),
            adapt(raw).getJSONArray("outbounds").toString())
    }

    @Test fun removedServiceInboundCannotCaptureAndroidTraffic() {
        val raw = """{"inbounds":[{"tag":"tun","protocol":"dokodemo-door"}],
          "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"block","protocol":"blackhole"}],
          "routing":{"rules":[{"type":"field","inboundTag":["tun"],"outboundTag":"block"}]}}"""
        val built = adapt(raw)
        assertNotEquals("tun", built.getJSONArray("inbounds").getJSONObject(0).getString("tag"))
        assertEquals(JSONObject(raw).getJSONObject("routing").toString(), built.getJSONObject("routing").toString())
    }
}
