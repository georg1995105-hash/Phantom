package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TcpEndpointTest {
    private fun profile(raw: String) = SubscriptionParser.parse(raw).servers.single()
    private val key = "vless://00000000-0000-4000-8000-000000000001@[2001:db8::7]:8443"
    private val peer = TcpEndpoint("2001:db8::7", 8443)

    @Test fun tcpMeasuresTheDialAddressAndNotTheHttpHostOrSni() {
        for (query in listOf("type=ws", "type=grpc", "type=xhttp&security=reality",
            "type=xhttp&security=tls&alpn=h2,h3", "type=raw"))
            assertEquals(peer, TcpEndpoint.forProfile(profile("$key?$query&host=cdn.test&sni=origin.test#Автовыбор")))
        assertEquals(TcpEndpoint("example.test", 9443),
            TcpEndpoint.forProfile(profile("anytls://secret@example.test:9443?sni=other.test")))
    }

    @Test fun udpAndSplitPathsMustGoThroughTheCore() {
        for (link in listOf("hy2://secret@host.test:443",
            "tuic://00000000-0000-4000-8000-000000000001:secret@host.test:443",
            "$key?type=xhttp&security=tls&alpn=h3", "$key?type=kcp",
            "$key?type=xhttp&extra=%7B%22downloadSettings%22%3A%7B%7D%7D"))
            assertNull(link.substringBefore(":"), TcpEndpoint.forProfile(profile(link)))
    }

    @Test fun fullJsonRequiresAReachableDefaultAndUnambiguousRoute() {
        val outbound = XrayConfigBuilder.shareOutbound(profile(key))
        val root = JSONObject().put("remarks", "Автовыбор").put("outbounds",
            JSONArray().put(JSONObject().put("protocol", "blackhole")).put(outbound))
        assertNull(TcpEndpoint.forProfile(profile(root.toString())))
        root.put("routing", JSONObject("""{"balancers":[{"tag":"choice","selector":["p"],"fallbackTag":"other"}]}"""))
        assertNull(TcpEndpoint.forProfile(profile(root.toString())))
        root.remove("routing")
        outbound.put("proxySettings", JSONObject().put("tag", "upstream"))
        assertNull(TcpEndpoint.forProfile(profile(root.toString())))
        outbound.remove("proxySettings")
        root.getJSONArray("outbounds").put(outbound)
        assertNull(TcpEndpoint.forProfile(profile(root.toString())))
    }

    @Test fun fullJsonDoesNotReportTcpOfAnOutboundBypassedByProviderRules() {
        val outbound = XrayConfigBuilder.shareOutbound(profile(key)).put("tag", "proxy")
        val direct = JSONObject().put("protocol", "freedom").put("tag", "direct")
        val root = JSONObject().put("outbounds", JSONArray().put(outbound).put(direct))
        // Xray defaults to the first outbound if a rule does not match.
        assertEquals(peer, TcpEndpoint.forProfile(profile(root.toString())))
        root.put("routing", JSONObject("""{"rules":[{"type":"field","domain":["full:direct.example"],
            "outboundTag":"direct"}]}"""))
        assertNull("A provider direct route may bypass this TCP server",
            TcpEndpoint.forProfile(profile(root.toString())))
        root.put("routing", JSONObject("""{"rules":[{"type":"field","outboundTag":"proxy"}]}"""))
        assertEquals(peer, TcpEndpoint.forProfile(profile(root.toString())))
        root.put("routing", JSONObject("""{"rules":[{"type":"field","balancerTag":"other"}]}"""))
        assertNull("A balancer needs an actual routed probe",
            TcpEndpoint.forProfile(profile(root.toString())))
    }

    @Test fun independentTcpCanMeasureOneEntryDespiteProviderRouting() {
        val outbound = XrayConfigBuilder.shareOutbound(profile(key)).put("tag", "proxy")
        val direct = JSONObject().put("protocol", "freedom").put("tag", "direct")
        val root = JSONObject().put("remarks", "🇳🇱 Нидерланды").put(
            "outbounds", JSONArray().put(outbound).put(direct))
        root.put("routing", JSONObject(
            """{"rules":[{"type":"field","domain":["full:example.test"],"outboundTag":"direct"}]}"""))
        val config = profile(root.toString())
        assertNull("Strict route-associated RTT must remain guarded", TcpEndpoint.forProfile(config))
        assertEquals("Independent physical TCP entry must ignore unrelated routing",
            peer, TcpEndpoint.independentEntryForProfile(config))
    }

    @Test fun independentTcpDoesNotSelectAnArbitraryBalancerOrUdpNode() {
        val first = XrayConfigBuilder.shareOutbound(profile(key)).put("tag", "a")
        val second = XrayConfigBuilder.shareOutbound(profile(
            "vless://00000000-0000-4000-8000-000000000001@second.example:443"))
            .put("tag", "b")
        val complex = JSONObject().put("outbounds", JSONArray().put(first).put(second))
            .put("routing", JSONObject(
                """{"balancers":[{"tag":"choice","selector":["a","b"]}]}"""))
        assertNull("Balancer endpoints must not be falsely collapsed to a TCP RTT",
            TcpEndpoint.independentEntryForProfile(profile(complex.toString())))
        assertNull(TcpEndpoint.independentEntryForProfile(profile(
            "hysteria2://secret@udp.example:443")))
        val h3 = XrayConfigBuilder.shareOutbound(profile(
            "$key?type=xhttp&security=tls&alpn=h3"))
        assertNull(TcpEndpoint.independentEntryForProfile(profile(
            JSONObject().put("outbounds", JSONArray().put(h3)).toString())))
    }

    @Test fun vmessKeepsItsEntryRemarkAndXhttpSettings() {
        val encoded = """{"add":"edge.test","port":"2443","id":"123","ps":"🇸🇪 Home","net":"xhttp","extra":{"noSSEHeader":true}}"""
        val server = profile("vmess://" + Base64.getEncoder().encodeToString(encoded.toByteArray()))
        assertEquals("🇸🇪 Home", server.name)
        assertEquals("🇸🇪", server.countryFlag)
        assertEquals("edge.test", server.address)
        assertEquals(2443, server.port)
        assertEquals(TcpEndpoint("edge.test", 2443), TcpEndpoint.forProfile(server))
        assertTrue(XrayConfigBuilder.shareOutbound(server).getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings").getJSONObject("extra").getBoolean("noSSEHeader"))
    }
}
