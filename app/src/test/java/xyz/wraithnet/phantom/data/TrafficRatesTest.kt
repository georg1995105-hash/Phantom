package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class TrafficRatesTest {
    @Test fun reportsAccurateTunnelRatesForMultipleOutbounds() {
        val report = "proxy,uplink,2048;proxy,downlink,4096;direct,downlink,2048;"
        assertEquals(TrafficRate(3072L, 1024L), TrafficRates.fromXrayReport(report, 2000L))
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
    }
}
