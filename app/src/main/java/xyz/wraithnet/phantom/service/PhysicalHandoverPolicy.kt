package xyz.wraithnet.phantom.service

/**
 * A default-network change is not equivalent to a crashed VPN engine.
 *
 * Android keeps the existing TUN alive. Xray/sing-box are allowed to re-dial
 * their physical sockets on the new default network without destroying that TUN.
 * Initial boot or recovery from a stopped engine still needs a fresh start.
 *
 * This policy deliberately does not use a third-party HTTPS endpoint as a
 * restart oracle: blocked services must not kill an otherwise useful VPN.
 */
internal object PhysicalHandoverPolicy {
    enum class Decision { IGNORE, BOOTSTRAP, PRESERVE_TUN, NETWORK_LOST }

    fun decide(
        requested: Boolean,
        running: Boolean,
        currentRoute: String?,
        previousRoute: String?,
    ): Decision = when {
        !requested -> Decision.IGNORE
        currentRoute == null -> if (running && previousRoute != null) Decision.NETWORK_LOST
            else Decision.IGNORE
        currentRoute == previousRoute -> Decision.IGNORE
        running -> Decision.PRESERVE_TUN
        else -> Decision.BOOTSTRAP
    }
}
