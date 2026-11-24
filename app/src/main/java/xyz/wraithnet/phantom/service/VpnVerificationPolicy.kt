package xyz.wraithnet.phantom.service

/**
 * Guard for background verification results. A late HTTPS reply from a
 * stopped/replaced VPN session cannot revive the UI's Connected status.
 *
 * Neither TLS success nor failure permits removal of the Android TUN.
 */
internal object VpnVerificationPolicy {
    fun canCommit(
        running: Boolean,
        revoked: Boolean,
        sessionMatches: Boolean,
        epochMatches: Boolean,
        jobActive: Boolean,
    ): Boolean = running && !revoked && sessionMatches && epochMatches && jobActive
}
