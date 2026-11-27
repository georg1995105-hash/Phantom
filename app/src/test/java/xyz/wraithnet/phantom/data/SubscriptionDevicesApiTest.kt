package xyz.wraithnet.phantom.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDevicesApiTest {
    @Test fun allRemnawaveSubscriptionsAreEligibleWithoutStorePurchase() {
        // Manual, gifted and ordinary subscriptions are indistinguishable as
        // capability URLs. There must be no reliance on a shop receipt or bot.
        val manual = "https://sub2.wraithnet.xyz/abcdefghijklmnop"
        val gifted = "https://sub2.wraithnet.xyz/AnotherLongToken_123"
        assertTrue(SubscriptionDevicesApi.supports(manual))
        assertTrue(SubscriptionDevicesApi.supports(gifted))
        assertEquals("abcdefghijklmnop", SubscriptionDevicesApi.shortUuid(manual))
        assertEquals("AnotherLongToken_123", SubscriptionDevicesApi.shortUuid(gifted))
        assertEquals("abcdefghijklmnop",
            SubscriptionDevicesApi.shortUuid("$manual/json"))
    }

    @Test fun importedOrMalformedUrlsCannotControlPhantomDevices() {
        for (source in listOf(
            "", "https://example.com/abcdefghijklmnop",
            "https://sub2.wraithnet.xyz.evil.example/abcdefghijklmnop",
            "https://sub2.wraithnet.xyz:8443/abcdefghijklmnop",
            "http://sub2.wraithnet.xyz/abcdefghijklmnop",
            "https://sub2.wraithnet.xyz/demo123",
            "https://sub2.wraithnet.xyz/abcdefghijklmnop/extra",
            "https://guest:pass@sub2.wraithnet.xyz/abcdefghijklmnop",
            "vless://uuid@example.com:443"
        )) {
            assertFalse(SubscriptionDevicesApi.supports(source))
            assertNull(SubscriptionDevicesApi.shortUuid(source))
        }
    }
}
