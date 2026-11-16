package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ShareLinkCodecTest {
    @Test fun titleBase64PrefixAndPlainText() {
        val encoded = Base64.getEncoder().encodeToString("Phantom VPN".toByteArray())
        assertEquals("Phantom VPN", SubscriptionHeaders.decodeTitle("base64:$encoded"))
        assertEquals("Phantom VPN", SubscriptionHeaders.decodeTitle("Phantom VPN"))
    }

    @Test fun percentDecoderPreservesPlusInPassword() {
        assertEquals("abc+def +ghi", ShareLinkCodec.percentDecode("abc+def%20%2Bghi"))
    }

    @Test fun shadowsocksSip002AndLegacyFormats() {
        val credentials = Base64.getEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:p+q:r".toByteArray())
        val sip = ShareLinkCodec.shadowsocks("ss://$credentials@example.com:8388#Test")
        assertEquals("example.com", sip.host)
        assertEquals(8388, sip.port)
        assertEquals("aes-256-gcm", sip.method)
        assertEquals("p+q:r", sip.password)

        val legacy = Base64.getEncoder().encodeToString(
            "chacha20-ietf-poly1305:secret@[2001:db8::1]:5443".toByteArray())
        val endpoint = ShareLinkCodec.shadowsocks("ss://$legacy")
        assertEquals("2001:db8::1", endpoint.host)
        assertEquals(5443, endpoint.port)
    }

    @Test fun malformedUriIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareLinkCodec.shadowsocks("ss://not-a-valid-server")
        }
    }
}
