package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class TrafficRatesTest {
    @Test fun reportsAccurateTunnelRatesForMultipleOutbounds() {
        val report = "proxy,uplink,2048;proxy,downlink,4096;direct,downlink,2048;"
        assertEquals(TrafficRate(3072L, 1024L), TrafficRates.fromXrayReport(report, 2000L))
    }

    @Test fun sessionTotalsPreserveSmallDeltasAndIgnoreBlockedTraffic() {
        val first = TrafficRates.bytesFromReport("proxy,downlink,1;block,uplink,4000;")!!
        val next = TrafficRates.bytesFromReport("direct,downlink,2;proxy,uplink,9;")!!
        assertEquals(TrafficBytes(3, 9), first + next)
        assertEquals(TrafficBytes(Long.MAX_VALUE, 9), TrafficBytes(Long.MAX_VALUE, 0) + next)
    }

    @Test fun zeroTrafficIsZeroAndBlockTrafficIsExcluded() {
        assertEquals(TrafficRate(0L, 0L), TrafficRates.fromXrayReport("", 1500L))
        assertEquals(TrafficRate(0L, 0L),
            TrafficRates.fromXrayReport("block,uplink,4096;", 1500L))
    }

    @Test fun invalidCountersDoNotDisplayFakeRates() {
        assertNull(TrafficRates.fromXrayReport("proxy,downlink,broken;", 1500))
        assertNull(TrafficRates.fromXrayReport("proxy,downlink,100;", 0))
        assertNull(TrafficRates.fromXrayReport("proxy,uplink,-10;", 1500))
        assertEquals("—", TrafficRates.label(-1))
        assertEquals("—", TrafficRates.labelBits(-1))
        assertEquals("0 Кбит/с", TrafficRates.labelBits(0))
        assertTrue(TrafficRates.labelBits(125_000).contains("Мбит/с"))
    }
}
