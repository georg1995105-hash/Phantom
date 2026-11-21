package xyz.wraithnet.phantom.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import xyz.wraithnet.phantom.data.Subscription
import org.junit.Test

class SubscriptionPresentationTest {
    @Test fun updateIntervalsKeepTheirPrecision() {
        assertEquals("Каждый час", intervalLabel(3600))
        assertEquals("Каждые 2 ч.", intervalLabel(7200))
        assertEquals("Каждые 90 мин.", intervalLabel(5400))
        assertEquals("Интервал 95 с", intervalLabel(95))
    }

    @Test fun expiryRemainingDaysAreRoundedCorrectly() {
        assertEquals(0L, daysLeft(1000L, 1000L))
        assertEquals(0L, daysLeft(999L, 1000L))
        assertEquals(1L, daysLeft(1001L, 1000L))
        assertEquals(1L, daysLeft(87400L, 1000L))
        assertEquals(2L, daysLeft(87401L, 1000L))
    }
    @Test fun unknownSubscriptionMetadataNeverInventsAnActivePlan() {
        val item = Subscription(id = "a", title = "Моя подписка",
            source = "https://example.org/sub", servers = emptyList())
        val access = subscriptionAccessSnapshot(item, 1000L)
        assertNull(access.daysRemaining)
        assertNull(access.usedBytes)
        assertNull(access.limitBytes)
        assertNull(access.progress)
    }

    @Test fun trafficAndRemainingDaysUseProviderValues() {
        val item = Subscription(id = "a", title = "Моя подписка",
            source = "https://example.org/sub", servers = emptyList(),
            expiresAt = 87401L, uploadBytes = 10, downloadBytes = 20, totalBytes = 100)
        val access = subscriptionAccessSnapshot(item, 1000L)
        assertEquals(2L, access.daysRemaining)
        assertEquals(30L, access.usedBytes)
        assertEquals(100L, access.limitBytes)
        assertEquals(0.3f, access.progress!!, 0.00001f)
    }

    @Test fun unlimitedOrHugeTrafficNeverOverflowsOrProducesFakePercentage() {
        val unlimited = Subscription(id = "u", title = "∞",
            source = "https://example.org/u", servers = emptyList(),
            totalBytes = 0L, uploadBytes = 50L)
        val unlimitedAccess = subscriptionAccessSnapshot(unlimited, 1000L)
        assertEquals(50L, unlimitedAccess.usedBytes)
        assertNull(unlimitedAccess.limitBytes)
        assertNull(unlimitedAccess.progress)

        val huge = unlimited.copy(uploadBytes = Long.MAX_VALUE,
            downloadBytes = 500L, totalBytes = 1024L, expiresAt = 999L)
        val hugeAccess = subscriptionAccessSnapshot(huge, 1000L)
        assertEquals(Long.MAX_VALUE, hugeAccess.usedBytes)
        assertEquals(1f, hugeAccess.progress!!, 0.00001f)
        assertEquals(0L, hugeAccess.daysRemaining)
    }

}
