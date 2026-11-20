package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Panel-independent profile imports: Marzban/3x-ui-style share URLs and lists. */
class UniversalSubscriptionImportTest {
    private val vless =
        "vless://00000000-0000-0000-0000-000000000001@vpn.example.org:443?type=xhttp&security=reality#Sweden"
    private val trojan =
        "trojan://examplePassword@proxy.example.org:443?security=tls#Netherlands"

    @Test fun requestsProviderJsonOnlyForThePhantomSubscriptionEndpoint() {
        val original = "https://sub2.wraithnet.xyz/testOnlyId016ABC"
        assertEquals("$original/json", SubscriptionFetcher.requestUrl(original))
        assertEquals("$original/json?platform=android",
            SubscriptionFetcher.requestUrl("$original/?platform=android"))
        assertEquals("$original/json", SubscriptionFetcher.requestUrl("$original/json"))
        val unchanged = listOf(
            "https://sub.example.org/testOnlyId016ABC",
            "https://sub2.wraithnet.xyz.example.org/testOnlyId016ABC",
            "https://sub2.wraithnet.xyz:8443/testOnlyId016ABC",
            "http://sub2.wraithnet.xyz/testOnlyId016ABC",
            "$original/clash",
            "https://sub2.wraithnet.xyz/",
        )
        unchanged.forEach { assertEquals(it, SubscriptionFetcher.requestUrl(it)) }
    }

    @Test fun importSingleVlessLink() {
        val parsed = SubscriptionParser.parse(vless)
        assertEquals(1, parsed.servers.size)
        assertEquals("vless", parsed.servers.single().scheme)
        assertEquals("Sweden", parsed.servers.single().name)
    }

    @Test fun importStandardBase64SubscriptionWithMultipleProtocols() {
        val content = "$vless\n$trojan\n"
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val parsed = SubscriptionParser.parse(encoded, "Other VPN subscription")
        assertEquals("Other VPN subscription", parsed.title)
        assertEquals(2, parsed.servers.size)
        assertEquals(listOf("vless", "trojan"), parsed.servers.map { it.scheme })
    }

    @Test fun importUrlSafeUnpaddedBase64Subscriptions() {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$vless\n$trojan".toByteArray(Charsets.UTF_8))
        assertEquals(2, SubscriptionParser.parse(encoded).servers.size)
    }

    @Test fun mixedSubscriptionKeepsTuicAnyTlsHysteria2AndVless() {
        val tuic = "tuic://00000000-0000-0000-0000-000000000002:secret@se.example.org:25443?sni=se.example.org#TUIC"
        val anytls = "anytls://secret@se.example.org:443?sni=se.example.org#AnyTLS"
        val hy2 = "hysteria2://secret@de.example.org:443?sni=de.example.org#Germany"
        val mixed = listOf(tuic, vless, hy2, anytls).joinToString("\n")
        val subscription = SubscriptionParser.parse(mixed)
        assertEquals(4, subscription.servers.size)
        assertEquals(listOf("tuic", "vless", "hysteria2", "anytls"),
            subscription.servers.map { it.scheme })
        assertTrue(subscription.servers.all { ProtocolSupport.isSupported(it.scheme) })
    }

    @Test fun unknownProtocolsAreNotMisrepresentedAsSupported() {
        val unsupported = "tuic://password@not-supported.example.org:443"
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionParser.parse(unsupported)
        }
    }
}
