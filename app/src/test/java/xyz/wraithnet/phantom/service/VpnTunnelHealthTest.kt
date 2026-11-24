package xyz.wraithnet.phantom.service

import org.junit.Assert.*
import org.junit.Test

class VpnTunnelHealthTest {
    @Test fun oneSuccessfulHttpsProbeVerifiesRoute() {
        assertEquals("connected", VpnTunnelHealth.afterHttpsProbes(true))
    }

    @Test fun allUnavailableControlSitesDoNotSignalDestroyedTun() {
        assertEquals("degraded", VpnTunnelHealth.afterHttpsProbes(false))
        assertTrue(VpnTunnelHealth.isActive(VpnTunnelHealth.DEGRADED))
    }

    @Test fun degradedTunnelRemainsStoppableButNotReportedVerified() {
        assertTrue(VpnTunnelHealth.isActive("connected"))
        assertTrue(VpnTunnelHealth.isActive("degraded"))
        assertFalse(VpnTunnelHealth.isActive("starting"))
        assertFalse(VpnTunnelHealth.isActive("error"))
        assertFalse(VpnTunnelHealth.isActive("disconnected"))
    }
}
