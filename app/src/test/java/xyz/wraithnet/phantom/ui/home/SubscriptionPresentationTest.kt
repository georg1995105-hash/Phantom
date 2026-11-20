package xyz.wraithnet.phantom.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionPresentationTest {
    @Test fun expiryRemainingDaysAreRoundedCorrectly() {
        assertEquals(0L, daysLeft(1000L, 1000L))
        assertEquals(0L, daysLeft(999L, 1000L))
        assertEquals(1L, daysLeft(1001L, 1000L))
        assertEquals(1L, daysLeft(87400L, 1000L))
        assertEquals(2L, daysLeft(87401L, 1000L))
    }
}
