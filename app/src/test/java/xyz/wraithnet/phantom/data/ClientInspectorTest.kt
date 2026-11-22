package xyz.wraithnet.phantom.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientInspectorTest {
    @Test fun tuicInspectorShowsRealOutboundWithoutInventedRuntimeSocket() {
        val profile = ServerProfile(
            id = "test",
            name = "TUIC demo",
            scheme = "tuic",
            raw = "tuic://0e30184e-81b9-4ac0-9e43-e47b9c6ad0aa:verySecret@" +
                "example.org:443?sni=example.org&alpn=h3"
        )
        val document = ClientConfigInspector.inspect(profile)
        val real = JSONObject(document.full)
        val masked = JSONObject(document.masked)
        assertEquals("sing-box · outbound", document.engine)
        assertEquals("tuic", real.getString("type"))
        assertFalse(real.has("inbounds"))
        assertEquals("verySecret", real.getString("password"))
        assertEquals("••••••", masked.getString("password"))
        assertEquals("••••••", masked.getString("uuid"))
        assertFalse(document.masked.contains("verySecret"))
        assertFalse(document.masked.contains("0e30184e"))
    }

    @Test fun xrayProviderJsonPreservesActualRouteWhileRedactingCredentials() {
        val provider = """
            {
              "outbounds": [
                {"tag":"proxy","protocol":"vless","settings":{"vnext":[
                  {"address":"example.org","port":443,"users":[{"id":"SECRET-ID","encryption":"none"}]}
                ]}},
                {"tag":"direct","protocol":"freedom"}
              ],
              "routing":{"rules":[{"type":"field","domain":["domain:example.com"],"outboundTag":"proxy"}]}
            }
        """.trimIndent()
        val result = ClientConfigInspector.inspect(
            ServerProfile(id="json",name="Provider",scheme="xray",raw=provider)
        )
        assertEquals("proxy", JSONObject(result.full).getJSONObject("routing")
            .getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        assertTrue(result.full.contains("SECRET-ID"))
        assertFalse(result.masked.contains("SECRET-ID"))
        assertEquals("••••••", JSONObject(result.masked).getJSONArray("outbounds")
            .getJSONObject(0).getJSONObject("settings").getJSONArray("vnext")
            .getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("id"))
    }

    @Test fun hysteriaAuthenticationIsMasked() {
        val doc = ClientConfigInspector.inspect(ServerProfile(
            id="hy", name="Demo", scheme="hysteria2",
            raw="hysteria2://myPassword@example.org:443?sni=example.org"
        ))
        assertFalse(doc.masked.contains("myPassword"))
        assertTrue(doc.full.contains("myPassword"))
    }

    @Test fun exitLookupOnlyAcceptsValidSuccessfulNetworkIdentities() {
        val valid = """{"success":true,"ip":"203.0.113.7","country_code":"NL",
            "connection":{"asn":12345,"org":"Example Network","isp":"Different ISP"}}"""
        val data = ExitNetworkLookup.parse(valid)
        assertNotNull(data)
        assertEquals("203.0.113.7", data!!.ip)
        assertEquals("NL", data.countryCode)
        assertEquals("Example Network", data.network)
        assertEquals("AS12345", data.asn)
        assertNull(ExitNetworkLookup.parse(valid.replace("\"success\":true", "\"success\":false")))
        assertNull(ExitNetworkLookup.parse(valid.replace("203.0.113.7", "not-an-ip")))
        assertNull(ExitNetworkLookup.parse(valid.replace("Example Network", "")
            .replace("Different ISP", "")))
    }
}
