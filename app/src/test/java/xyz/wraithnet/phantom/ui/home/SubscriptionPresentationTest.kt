package xyz.wraithnet.phantom.ui.home

import org.junit.Assert.assertEquals
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
}
