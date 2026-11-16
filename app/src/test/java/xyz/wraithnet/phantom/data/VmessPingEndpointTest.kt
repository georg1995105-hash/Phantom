package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VmessPingEndpointTest {
    @Test fun encodedVmessLinkResolvesCorrectEndpoint() {
        val json = """{"v":"2","ps":"Demo","add":"vpn.example.org","port":"1443","id":"00000000-0000-0000-0000-000000000001","aid":"0"}"""
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        val server = ServerProfile(id="1",name="VMess",scheme="vmess",raw="vmess://$encoded")
        assertEquals("vpn.example.org" to 1443, LatencyProbe.endpoint(server))
    }
    @Test fun malformedEndpointDoesNotMisleadUser() {
        val server = ServerProfile(id="2",name="VMess",scheme="vmess",raw="vmess://not_base64")
        assertNull(LatencyProbe.endpoint(server))
    }
}
