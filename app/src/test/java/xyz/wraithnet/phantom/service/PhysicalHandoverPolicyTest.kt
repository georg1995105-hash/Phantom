package xyz.wraithnet.phantom.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalHandoverPolicyTest {
    @Test fun wifiToLteDoesNotStopRunningTun() {
        assertEquals(PhysicalHandoverPolicy.Decision.PRESERVE_TUN, PhysicalHandoverPolicy.decide(true, true, "mobile", "wifi"))
    }

    @Test fun lteToWifiDoesNotStopRunningTun() {
        assertEquals(PhysicalHandoverPolicy.Decision.PRESERVE_TUN, PhysicalHandoverPolicy.decide(true, true, "wifi", "mobile"))
    }

    @Test fun initialValidNetworkBootstrapsStoppedCore() {
        assertEquals(PhysicalHandoverPolicy.Decision.BOOTSTRAP, PhysicalHandoverPolicy.decide(true, false, "wifi", null))
    }

    @Test fun newValidNetworkCanRestartAlreadyStoppedCore() {
        assertEquals(PhysicalHandoverPolicy.Decision.BOOTSTRAP, PhysicalHandoverPolicy.decide(true, false, "mobile", "wifi"))
    }

    @Test fun repeatedCallbackDoesNotRestartOrTouchCore() {
        assertEquals(PhysicalHandoverPolicy.Decision.IGNORE, PhysicalHandoverPolicy.decide(true, true, "wifi", "wifi"))
    }

    @Test fun offlineAndUserDisconnectNeverStartCore() {
        assertEquals(PhysicalHandoverPolicy.Decision.IGNORE, PhysicalHandoverPolicy.decide(true, true, null, "wifi"))
        assertEquals(PhysicalHandoverPolicy.Decision.IGNORE, PhysicalHandoverPolicy.decide(true, false, null, null))
        assertEquals(PhysicalHandoverPolicy.Decision.IGNORE, PhysicalHandoverPolicy.decide(false, false, "wifi", "mobile"))
        assertEquals(PhysicalHandoverPolicy.Decision.IGNORE, PhysicalHandoverPolicy.decide(false, true, "wifi", "mobile"))
    }
}
