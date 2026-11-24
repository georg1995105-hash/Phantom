package xyz.wraithnet.phantom.service

/**
 * A valid Android TUN + started core is not the same as an externally verified
 * HTTPS route. When third-party sites cannot be reached, keep the TUN installed
 * and report degraded rather than silently exposing included apps to direct IP.
 *
 * A native start exception is a separate failure handled by the service.
 */
internal object VpnTunnelHealth {
    const val VERIFIED = "connected"
    const val DEGRADED = "degraded"

    fun afterHttpsProbes(anyVerified: Boolean): String =
        if (anyVerified) VERIFIED else DEGRADED

    fun isActive(status: String): Boolean = status == VERIFIED || status == DEGRADED
}
