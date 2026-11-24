package xyz.wraithnet.phantom.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalHandoverPolicyTest {
    private val D = PhysicalHandoverPolicy.Decision

    @Test fun wifiToLteDoesNotStopRunningTun() {
        assertEquals(D.PRESERVE_TUN, PhysicalHandoverPolicy.decide(true, true, "mobile", "wifi"))
    }

    @Test fun lteToWifiDoesNotStopRunningTun() {
        assertEquals(D.PRESERVE_TUN, PhysicalHandoverPolicy.decide(true, true, "wifi", "mobile"))
    }

    @Test fun initialValidNetworkBootstrapsStoppedCore() {
        assertEquals(D.BOOTSTRAP, PhysicalHandoverPolicy.decide(true, false, "wifi", null))
    }

    @Test fun newValidNetworkCanRestartAlreadyStoppedCore() {
        assertEquals(D.BOOTSTRAP, PhysicalHandoverPolicy.decide(true, false, "mobile", "wifi"))
    }

    @Test fun repeatedCallbackDoesNotRestartOrTouchCore() {
        assertEquals(D.IGNORE, PhysicalHandoverPolicy.decide(true, true, "wifi", "wifi"))
    }

    @Test fun offlineAndUserDisconnectNeverStartCore() {
        assertEquals(D.IGNORE, PhysicalHandoverPolicy.decide(true, true, null, "wifi"))
        assertEquals(D.IGNORE, PhysicalHandoverPolicy.decide(true, false, null, null))
        assertEquals(D.IGNORE, PhysicalHandoverPolicy.decide(false, false, "wifi", "mobile"))
        assertEquals(D.IGNORE, PhysicalHandoverPolicy.decide(false, true, "wifi", "mobile"))
    }
}
