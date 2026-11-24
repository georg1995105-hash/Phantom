package xyz.wraithnet.phantom.service

import org.junit.Assert.*
import org.junit.Test

class SingBoxRecoveryPolicyTest {
    private fun eligible(
        running: Boolean = true,
        requested: Boolean = true,
        revoked: Boolean = false,
        sameEpoch: Boolean = true,
        coreAndTunAlive: Boolean = true,
        workerAbsent: Boolean = true,
        hasProfileAndPinnedPort: Boolean = true,
        hasPhysicalNetwork: Boolean = true,
        attempts: Int = 0,
    ) = SingBoxRecoveryPolicy.canAttempt(
        running, requested, revoked, sameEpoch, coreAndTunAlive, workerAbsent,
        hasProfileAndPinnedPort, hasPhysicalNetwork, attempts
    )

    @Test fun cappedRetriesAndBackoff() {
        assertTrue(eligible(attempts = 0))
        assertTrue(eligible(attempts = 1))
        assertTrue(eligible(attempts = 2))
        assertFalse(eligible(attempts = 3))
        assertFalse(eligible(attempts = 4))
        assertFalse(eligible(attempts = -1))
        assertEquals(500L, SingBoxRecoveryPolicy.delayBeforeAttemptMs(0))
        assertEquals(1_500L, SingBoxRecoveryPolicy.delayBeforeAttemptMs(1))
        assertEquals(4_000L, SingBoxRecoveryPolicy.delayBeforeAttemptMs(2))
    }

    @Test fun neverResurrectStoppedRevokedOrReplacedTunnel() {
        assertFalse(eligible(running = false))
        assertFalse(eligible(requested = false))
        assertFalse(eligible(revoked = true))
        assertFalse(eligible(sameEpoch = false))
        assertFalse(eligible(coreAndTunAlive = false))
        assertFalse(eligible(workerAbsent = false))
    }

    @Test fun missingPhysicalNetworkOrOriginalPortMustFailClosed() {
        assertFalse(eligible(hasPhysicalNetwork = false))
        assertFalse(eligible(hasProfileAndPinnedPort = false))
    }
}
