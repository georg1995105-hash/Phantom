package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionHeadersTest {
    @Test fun providerAnnounceIsDecodedAndCanBeRemoved() {
        val announce = "Объявление провайдера: подписка обновлена"
        val encoded = Base64.getEncoder().encodeToString(announce.toByteArray(Charsets.UTF_8))
        assertEquals(announce, SubscriptionHeaders.decodeAnnouncement("base64:$encoded"))
        assertEquals(announce, SubscriptionHeaders.decodeAnnouncement(announce))
        assertNull(SubscriptionHeaders.decodeAnnouncement("0"))
        assertNull(SubscriptionHeaders.decodeAnnouncement("base64:MA=="))
        assertNull(SubscriptionHeaders.decodeAnnouncement("base64:!!!"))
        assertEquals(200, SubscriptionHeaders.decodeAnnouncement("X".repeat(250))?.length)
    }

    @Test fun providerLinksOnlyOpenSafeWebSchemes() {
        assertEquals("https://t.me/phantom", SubscriptionHeaders.safeHttpUrl("https://t.me/phantom"))
        assertEquals("https://example.org/client", SubscriptionHeaders.safeHttpUrl(" https://example.org/client "))
        for (link in listOf("javascript:alert(1)", "intent://hi", "file:///sdcard/a",
            "https://name:password@example.com/secret", "https://example.org\nHost: evil",
            "https://", "")) assertNull(SubscriptionHeaders.safeHttpUrl(link))
    }
}
