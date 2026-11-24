package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectivityProbePlanTest {
    @Test fun normalServersUseBoundedIndependentHttpsTargets() {
        val config = JSONObject().put("outbounds", JSONArray()).toString()
        val steps = ConnectivityProbePlan.forConfig(config)
        assertEquals(4, steps.size)
        assertEquals("https://www.cloudflare.com/cdn-cgi/trace", steps[0].url)
        assertEquals(200, steps[0].expectedStatus)
        assertEquals(204, steps[1].expectedStatus)
        assertEquals(0L, steps.sumOf { it.pauseBeforeMs })
        assertTrue(steps.all { it.url.startsWith("https://") })
        assertTrue(steps.all { it.timeoutMs in 1000..6000 })
    }

    @Test fun smartBalancersWaitForObserverButNeverRetryIndefinitely() {
        val balancer = JSONObject().put("tag", "smart-nl")
            .put("selector", JSONArray().put("nl-direct").put("relay-fi"))
        val config = JSONObject()
            .put("routing", JSONObject()
                .put("balancers", JSONArray().put(balancer)))
            .put("burstObservatory", JSONObject()
                .put("subjectSelector", JSONArray().put("nl-direct").put("relay-fi")))
        val steps = ConnectivityProbePlan.forConfig(config.toString())
        assertEquals(6, steps.size)
        assertEquals(2, steps.count { it.pauseBeforeMs > 0L })
        assertEquals(200, steps[4].expectedStatus)
        assertEquals("https://www.cloudflare.com/cdn-cgi/trace", steps[4].url)
        assertTrue(steps.sumOf { it.timeoutMs.toLong() + it.pauseBeforeMs } <= 27_000L)
        assertTrue(steps.none { it.url.startsWith("http://") })
    }

    @Test fun observerWithoutBalancerAlsoGetsTheBoundedWarmup() {
        val config = JSONObject().put("observatory", JSONObject().put("subjectSelector",
            JSONArray().put("proxy"))).toString()
        assertEquals(6, ConnectivityProbePlan.forConfig(config).size)
    }

    @Test fun localAuthenticatedCheckRoutesThroughSameOutboundAsAndroidTun() {
        val vless = ServerProfile("test", "Test", "vless",
            raw = "vless://00000000-0000-0000-0000-000000000001@example.org:443?type=xhttp&security=tls")
        val base = XrayConfigBuilder.build(vless)
        val root = JSONObject(XrayConfigBuilder.withConnectivityCheck(base, 39221, "local-secret"))
        val inbounds = root.getJSONArray("inbounds")
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
        val check = inbounds.getJSONObject(1)
        assertEquals("http", check.getString("protocol"))
        assertEquals("127.0.0.1", check.getString("listen"))
        assertEquals("local-secret", check.getJSONObject("settings")
            .getJSONArray("accounts").getJSONObject(0).getString("pass"))
        val tag = check.getString("tag")
        assertTrue(tag.startsWith("phantom-check-"))
        val general = root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        // A basic share link routes ALL TCP/UDP inbounds through proxy, rather
        // than restricting its rule to the Android TUN tag. The loopback HTTP
        // check is covered by this same rule without a separate inboundTag.
        assertEquals("proxy", general.getString("outboundTag"))
        assertFalse(general.has("inboundTag"))
        assertTrue(general.getString("network").contains("tcp"))
    }

    @Test fun smartProviderBalancerRemainsFailClosedDuringHttpCheck() {
        val raw = """{"inbounds":[{"tag":"socks","protocol":"socks"}],
            "outbounds":[{"tag":"proxy","protocol":"vless"},
                {"tag":"block","protocol":"blackhole"}],
            "routing":{"balancers":[{"tag":"smart-nl","selector":["proxy"],
                "fallbackTag":"block","strategy":{"type":"leastPing"}}],
                "rules":[{"type":"field","inboundTag":["socks"],
                    "balancerTag":"smart-nl"}]},
            "burstObservatory":{"subjectSelector":["proxy"]}}"""
        val profile = SubscriptionParser.parse(raw).servers.single()
        val base = XrayConfigBuilder.build(profile)
        val checked = JSONObject(XrayConfigBuilder.withConnectivityCheck(base, 39221, "secret"))
        val extraTag = checked.getJSONArray("inbounds").getJSONObject(1).getString("tag")
        val routing = checked.getJSONObject("routing")
        val balancer = routing.getJSONArray("balancers").getJSONObject(0)
        assertEquals("block", balancer.getString("fallbackTag"))
        assertEquals("smart-nl", routing.getJSONArray("rules").getJSONObject(0)
            .getString("balancerTag"))
        val tags = routing.getJSONArray("rules").getJSONObject(0).getJSONArray("inboundTag")
        assertTrue((0 until tags.length()).any { tags.getString(it) == extraTag })
        assertEquals(6, ConnectivityProbePlan.forConfig(checked.toString()).size)
    }
}
