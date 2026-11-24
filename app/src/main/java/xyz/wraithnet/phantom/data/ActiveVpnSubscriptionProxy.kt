package xyz.wraithnet.phantom.data

import java.security.MessageDigest
import android.content.Context
import xyz.wraithnet.phantom.service.VpnRuntimeBridge

/**
 * Local authenticated HTTP proxy attached to the *running* Xray/TUN session.
 *
 * Phantom excludes its own UID from Android VPN routing to prevent the native
 * core's physical sockets from looping back into TUN. Manual subscription
 * refreshes can nevertheless use the selected node by explicitly dialing this
 * loopback HTTP inbound. Never fall back to a direct request after proxy failure.
 *
 * No subscription URLs or credentials are persisted here.
 */
internal object ActiveVpnSubscriptionProxy {
    data class Route(val port: Int, val password: String, val profileDigest: String)

    @Volatile private var active: Route? = null

    private fun digest(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun publish(port: Int, password: String, profileRaw: String) {
        require(port in 1024..65535 && password.isNotBlank() && profileRaw.isNotBlank())
        active = Route(port, password, digest(profileRaw))
    }

    internal fun hasRoute(): Boolean = active != null

    internal fun forDigest(profileDigest: String): Route? =
        active?.takeIf { it.profileDigest == profileDigest }

    fun forProfile(context: Context, profileRaw: String): Route? =
        if (profileRaw.isBlank()) null
        else VpnRuntimeBridge.routeForDigest(context, digest(profileRaw))

    fun isActive(context: Context, route: Route): Boolean =
        VpnRuntimeBridge.isActive(context, route)

    fun forProfile(profileRaw: String): Route? {
        if (profileRaw.isBlank()) return null
        return active?.takeIf { it.profileDigest == digest(profileRaw) }
    }

    fun isActive(route: Route): Boolean = active == route

    fun clear() {
        active = null
    }
}
