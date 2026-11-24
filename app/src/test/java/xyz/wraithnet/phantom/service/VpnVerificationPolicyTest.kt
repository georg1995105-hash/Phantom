package xyz.wraithnet.phantom.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnVerificationPolicyTest {
    @Test fun stillRunningExactSessionCanPublishProbeOutcome() {
        assertTrue(VpnVerificationPolicy.canCommit(
            running = true, revoked = false, sessionMatches = true,
            epochMatches = true, jobActive = true))
    }

    @Test fun lateSuccessAfterStopCannotReviveConnection() {
        assertFalse(VpnVerificationPolicy.canCommit(
            running = false, revoked = false, sessionMatches = true,
            epochMatches = false, jobActive = false))
    }

    @Test fun lateSuccessAfterProfileSwitchCannotOverwriteNewConnection() {
        assertFalse(VpnVerificationPolicy.canCommit(
            running = true, revoked = false, sessionMatches = false,
            epochMatches = false, jobActive = true))
    }

    @Test fun revokedVpnNeverAcceptsLateVerifiedState() {
        assertFalse(VpnVerificationPolicy.canCommit(
            running = true, revoked = true, sessionMatches = true,
            epochMatches = true, jobActive = true))
    }

    @Test fun commitRequiresAllFiveIndependentGuards() {
        // Every combination with even one broken guard must fail closed.
        for (bits in 0..31) {
            val running = bits and 1 != 0
            val notRevoked = bits and 2 != 0
            val sameSession = bits and 4 != 0
            val sameEpoch = bits and 8 != 0
            val active = bits and 16 != 0
            val allowed = VpnVerificationPolicy.canCommit(
                running, !notRevoked, sameSession, sameEpoch, active)
            if (bits == 31) assertTrue(allowed) else assertFalse(allowed)
        }
    }

    @Test fun cancelledProbeCannotChangeLiveVpnState() {
        assertFalse(VpnVerificationPolicy.canCommit(
            running = true, revoked = false, sessionMatches = true,
            epochMatches = true, jobActive = false))
    }
}
