package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class TrafficRatesTest {
    @Test fun measuresBothDirectionsPerSecond() {
        val start = TrafficSnapshot(1000L, 20000L, 30000L)
        val end = TrafficSnapshot(3000L, 22048L, 34096L)
        assertEquals(TrafficRate(1024L, 2048L), TrafficRates.between(start, end))
    }

    @Test fun unsupportedOrResetCountersNeverDisplayFakeNumbers() {
        assertNull(TrafficRates.between(
            TrafficSnapshot(1000, -1, 100), TrafficSnapshot(3000, -1, 200)))
        assertNull(TrafficRates.between(
            TrafficSnapshot(1000, 100, 100), TrafficSnapshot(3000, 50, 120)))
        assertNull(TrafficRates.between(
            TrafficSnapshot(1000, 100, 100), TrafficSnapshot(1000, 110, 110)))
    }

    @Test fun labelHandlesUnavailableCounters() {
        assertEquals("—", TrafficRates.label(-1))
        assertTrue(TrafficRates.label(1024).contains("КБ/с"))
    }
}
