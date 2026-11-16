package xyz.wraithnet.phantom.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VpnDnsSettingsTest {
    @Test fun defaultPreservesProviderJson() {
        val original = """{"dns":{"servers":[{"address":"https://provider.example/dns-query"}]}}"""
        assertEquals(original, VpnDnsSettings().applyToXray(original))
    }

    @Test fun customOverridesOnlyDnsServersAndPreservesOutbound() {
        val source = """{"outbounds":[{"protocol":"vless","settings":{"id":"secret"}}],"dns":{"hosts":{"domain.test":"127.0.0.1"}}}"""
        val settings = VpnDnsSettings(VpnDnsSettings.Mode.CUSTOM,
            "9.9.9.9", "149.112.112.112")
        assertNull(settings.validate())
        val root = JSONObject(settings.applyToXray(source))
        assertEquals("secret", root.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings").getString("id"))
        assertEquals(2, root.getJSONObject("dns").getJSONArray("servers").length())
        assertEquals("127.0.0.1", root.getJSONObject("dns")
            .getJSONObject("hosts").getString("domain.test"))
    }

    @Test fun invalidIpsAreRejectedWithoutNetworkLookup() {
        for (value in listOf("8.8.8.999", "8.8.8.8.bad", "foo.com",
            "192.168.01.2", "127.0.0.1:1234", "1.1.1.1\\n")) {
            assertFalse(VpnDnsSettings.isIpLiteral(value))
        }
        assertTrue(VpnDnsSettings.isIpLiteral("1.1.1.1"))
        assertTrue(VpnDnsSettings.isIpLiteral("2001:4860:4860::8888"))
        assertNotNull(VpnDnsSettings(VpnDnsSettings.Mode.CUSTOM,"bad").validate())
    }
}
