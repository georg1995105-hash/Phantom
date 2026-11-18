package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class LatencyTransportTest {
    private fun json(raw: String) = ServerProfile(
        id = "test", name = "Transport", scheme = "xray", raw = raw)

    @Test fun tcpVlessFromXrayJsonCanUseTcp() {
        val server = json("""{"outbounds":[{"protocol":"vless",
            "settings":{"vnext":[{"address":"tcp.example.org","port":443}]},
            "streamSettings":{"network":"tcp","security":"reality"}}]}""")
        assertFalse(LatencyProbe.needsTunnel(server))
        assertEquals("tcp.example.org" to 443, LatencyProbe.endpoint(server))
        assertEquals("TCP · 48 мс", LatencyProbe.PingResult(48, "TCP").label)
    }

    @Test fun hysteria2InsideXrayJsonMustNeverBeTcpProbed() {
        val server = json("""{"outbounds":[{"tag":"proxy","protocol":"hysteria",
            "settings":{"version":2,"address":"hy.example.org","port":443},
            "streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2}}}]}""")
        assertTrue(LatencyProbe.needsTunnel(server))
        assertEquals("VPN · 92 мс", LatencyProbe.PingResult(92, "VPN").label)
    }

    @Test fun h3XhttpInsideXrayJsonMustNeverBeTcpProbed() {
        val server = json("""{"outbounds":[{"protocol":"vless","streamSettings":{
            "network":"xhttp","security":"tls","tlsSettings":{"alpn":["h3"]}}}]}""")
        assertTrue(LatencyProbe.needsTunnel(server))
    }

    @Test fun h2XhttpMayBeTcpProbed() {
        val server = json("""{"outbounds":[{"protocol":"vless","streamSettings":{
            "network":"xhttp","security":"tls","tlsSettings":{"alpn":["h2","http/1.1"]}}}]}""")
        assertFalse(LatencyProbe.needsTunnel(server))
    }

    @Test fun h3XhttpShareLinkMustNeverBeTcpProbed() {
        val server = ServerProfile(id="vless", name="H3", scheme="vless",
            raw="vless://1234@test.example.org:443?type=xhttp&security=tls&alpn=h3#H3")
        assertTrue(LatencyProbe.needsTunnel(server))
    }

    @Test fun tcpAndVpnLabelsDoNotMisrepresentSource() {
        assertEquals("TCP · 66 мс", LatencyProbe.PingResult(66, "TCP").label)
        assertEquals("VPN · 66 мс", LatencyProbe.PingResult(66, "VPN").label)
    }

    @Test fun tuicAndHysteria2RequireConnectedVerification() {
        for (scheme in listOf("tuic", "hy2", "hysteria2")) {
            val server = ServerProfile(id=scheme, name=scheme, scheme=scheme,
                raw="$scheme://secret@vpn.example.org:443")
            assertTrue(LatencyProbe.needsTunnel(server))
        }
    }
}
