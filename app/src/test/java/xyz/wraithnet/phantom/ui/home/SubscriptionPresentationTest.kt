package xyz.wraithnet.phantom.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionPresentationTest {
    @Test fun serverCountUsesNaturalRussianForms() {
        val expectations = listOf(
            0 to "0 серверов", 1 to "1 сервер", 2 to "2 сервера",
            4 to "4 сервера", 5 to "5 серверов", 11 to "11 серверов",
            14 to "14 серверов", 15 to "15 серверов", 21 to "21 сервер",
            22 to "22 сервера", 25 to "25 серверов", 101 to "101 сервер"
        )
        for ((count, label) in expectations) {
            assertEquals(label, serverCountLabel(count))
        }
    }

    @Test fun expiryRemainingDaysAreRoundedCorrectly() {
        assertEquals(0L, daysLeft(1000L, 1000L))
        assertEquals(0L, daysLeft(999L, 1000L))
        assertEquals(1L, daysLeft(1001L, 1000L))
        assertEquals(1L, daysLeft(87400L, 1000L))
        assertEquals(2L, daysLeft(87401L, 1000L))
    }
}
