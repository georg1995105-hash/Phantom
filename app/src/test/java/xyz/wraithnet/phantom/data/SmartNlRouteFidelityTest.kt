package xyz.wraithnet.phantom.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Offline, synthetic replica of ADMIN smart NL semantics: direct NL or
 * Finland transit to NL, never Finland as final exit. No production secrets.
 *
 * These assertions establish structural route fidelity, NOT a real NL exit IP.
 */
class SmartNlRouteFidelityTest {
    private val raw = """{
      "inbounds": [
        {"tag":"monitor","protocol":"dokodemo-door","port":1081},
        {"tag":"socks","protocol":"socks","port":1080}
      ],
      "outbounds": [
        {"tag":"nl-route-direct","protocol":"vless",
          "settings":{"vnext":[{"address":"nl.example.invalid","port":443,
            "users":[{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}]}]}},
        {"tag":"nl-route-fi","protocol":"vless",
          "settings":{"vnext":[{"address":"nl.example.invalid","port":443,
            "users":[{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}]}]},
          "proxySettings":{"tag":"fi-relay"}},
        {"tag":"fi-relay","protocol":"vless",
          "settings":{"vnext":[{"address":"fi.example.invalid","port":443,
            "users":[{"id":"00000000-0000-0000-0000-000000000002","encryption":"none"}]}]}},
        {"tag":"block","protocol":"blackhole"}
      ],
      "routing":{
        "domainStrategy":"AsIs",
        "rules":[
          {"type":"field","inboundTag":["monitor"],"outboundTag":"block"},
          {"type":"field","inboundTag":["socks"],"balancerTag":"smart-nl"}
        ],
        "balancers":[{"tag":"smart-nl","selector":["nl-route-"],
          "fallbackTag":"block","strategy":{"type":"leastPing"}}]
      },
      "burstObservatory":{"subjectSelector":["nl-route-"],
        "pingConfig":{"sampling":2,"timeout":"3s"}},
      "dns":{"tag":"provider-dns","servers":["1.1.1.1"]}
    }"""

    @Test fun fullJsonKeepsBothNlExitsAndFinlandTransitOnly() {
        val source = JSONObject(raw)
        val built = JSONObject(XrayConfigBuilder.build(
            ServerProfile("smart", "ADMIN NL", "xray", raw = raw)))
        for (section in listOf("outbounds", "routing", "burstObservatory", "dns")) {
            assertEquals(source.get(section).toString(), built.get(section).toString())
        }
        assertEquals("socks", built.getJSONArray("inbounds").getJSONObject(0).getString("tag"))
        val outputs = built.getJSONArray("outbounds")
        assertEquals("nl.example.invalid", outputs.getJSONObject(0)
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("nl.example.invalid", outputs.getJSONObject(1)
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("fi-relay", outputs.getJSONObject(1)
            .getJSONObject("proxySettings").getString("tag"))
        assertEquals("fi.example.invalid", outputs.getJSONObject(2)
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("blackhole", outputs.getJSONObject(3).getString("protocol"))
    }

    @Test fun authenticatedProbeFollowsSmartNlBalancerAndNeverMonitorRule() {
        val built = XrayConfigBuilder.build(
            ServerProfile("smart", "ADMIN NL", "xray", raw = raw))
        val checked = JSONObject(XrayConfigBuilder.withConnectivityCheck(built, 31901, "synthetic"))
        val rules = checked.getJSONObject("routing").getJSONArray("rules")
        val probe = checked.getJSONArray("inbounds").getJSONObject(1).getString("tag")
        assertEquals("monitor", rules.getJSONObject(0).getJSONArray("inboundTag").getString(0))
        assertEquals(1, rules.getJSONObject(0).getJSONArray("inboundTag").length())
        assertEquals("smart-nl", rules.getJSONObject(1).getString("balancerTag"))
        assertEquals(probe, rules.getJSONObject(1).getJSONArray("inboundTag").getString(1))
        assertEquals("block", checked.getJSONObject("routing").getJSONArray("balancers")
            .getJSONObject(0).getString("fallbackTag"))
    }

    @Test fun providerDnsAdapterKeepsSmartBalancerAndTargetsActualTunTag() {
        val built = XrayConfigBuilder.build(
            ServerProfile("smart", "ADMIN NL", "xray", raw = raw))
        val adapted = JSONObject(requireNotNull(XrayConfigBuilder.withProviderTunDns(built)))
        val route = adapted.getJSONObject("routing")
        assertEquals("socks", route.getJSONArray("rules").getJSONObject(0)
            .getJSONArray("inboundTag").getString(0))
        assertEquals("smart-nl", route.getJSONArray("rules").getJSONObject(2)
            .getString("balancerTag"))
        assertEquals("block", route.getJSONArray("balancers").getJSONObject(0)
            .getString("fallbackTag"))
    }
}
