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

    @Test fun cancelledProbeCannotChangeLiveVpnState() {
        assertFalse(VpnVerificationPolicy.canCommit(
            running = true, revoked = false, sessionMatches = true,
            epochMatches = true, jobActive = false))
    }
}
