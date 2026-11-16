package xyz.wraithnet.phantom.service

import org.junit.Assert.*
import org.junit.Test

class VpnBootPolicyTest {
    @Test fun bootIsOffByDefault() {
        assertFalse(VpnBootPolicy.mayStart(false, true, true))
    }

    @Test fun existingProfileAndAndroidConsentAreBothRequired() {
        assertFalse(VpnBootPolicy.mayStart(true, false, true))
        assertFalse(VpnBootPolicy.mayStart(true, true, false))
        assertTrue(VpnBootPolicy.mayStart(true, true, true))
    }
}
