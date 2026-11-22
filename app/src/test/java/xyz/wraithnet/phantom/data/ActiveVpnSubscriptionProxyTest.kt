package xyz.wraithnet.phantom.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActiveVpnSubscriptionProxyTest {
    @Test fun selectedNodeIsRequiredEvenWhenTheSubscriptionBelongsToAnotherProvider() {
        ActiveVpnSubscriptionProxy.clear()
        try {
            ActiveVpnSubscriptionProxy.publish(32844, "local-secret", "vless://germany")
            val germany = requireNotNull(ActiveVpnSubscriptionProxy.forProfile("vless://germany"))
            assertEquals(32844, germany.port)
            assertEquals("local-secret", germany.password)
            assertNull(ActiveVpnSubscriptionProxy.forProfile("vless://poland"))
            assertNull(ActiveVpnSubscriptionProxy.forProfile(""))
            assertTrue(ActiveVpnSubscriptionProxy.isActive(germany))
        } finally {
            ActiveVpnSubscriptionProxy.clear()
        }
    }

    @Test fun reconnectRevokesTheOldCountryAndProxyCredentials() {
        ActiveVpnSubscriptionProxy.clear()
        try {
            ActiveVpnSubscriptionProxy.publish(32844, "first-secret", "vless://germany")
            val old = requireNotNull(ActiveVpnSubscriptionProxy.forProfile("vless://germany"))
            ActiveVpnSubscriptionProxy.publish(32845, "second-secret", "tuic://sweden")
            assertFalse(ActiveVpnSubscriptionProxy.isActive(old))
            assertNull(ActiveVpnSubscriptionProxy.forProfile("vless://germany"))
            assertEquals(32845, ActiveVpnSubscriptionProxy.forProfile("tuic://sweden")?.port)
            ActiveVpnSubscriptionProxy.clear()
            assertNull(ActiveVpnSubscriptionProxy.forProfile("tuic://sweden"))
        } finally {
            ActiveVpnSubscriptionProxy.clear()
        }
    }

    @Test fun singBoxBridgeGetsTheSameAuthenticatedRefreshInboundAsXray() {
        val original = XrayConfigBuilder.buildSocksBridge(22345)
        val root = JSONObject(XrayConfigBuilder.withConnectivityCheck(original, 33345, "test-secret"))
        val inbounds = root.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("tag"))
        val proxy = inbounds.getJSONObject(1)
        assertEquals("http", proxy.getString("protocol"))
        assertEquals("127.0.0.1", proxy.getString("listen"))
        assertEquals(33345, proxy.getInt("port"))
        val user = proxy.getJSONObject("settings").getJSONArray("accounts").getJSONObject(0)
        assertEquals("phantom", user.getString("user"))
        assertEquals("test-secret", user.getString("pass"))
        assertEquals("proxy", root.getJSONObject("routing").getJSONArray("rules")
            .getJSONObject(0).getString("outboundTag"))
    }
}
