package xyz.wraithnet.phantom.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import xyz.wraithnet.phantom.data.ActiveVpnSubscriptionProxy

/**
 * App-private Binder bridge to the process that OWNS the active VPN core.
 *
 * This provider is exported=false and runs in :vpn. It does not persist the
 * loopback proxy password, raw subscription, raw profile, or configuration.
 * UI death does not affect the VPN process; VPN process death clears its RAM.
 */
internal object VpnRuntimeBridge {
    data class Snapshot(val status: String, val connectedSince: Long, val available: Boolean = true,
        val profileDigest: String? = null) {
        val running: Boolean get() = PhantomVpnService.isTunnelActive(status)
    }

    @Volatile private var actualStatus = PhantomVpnService.STATUS_DISCONNECTED
    @Volatile private var actualConnectedSince = 0L

    fun publish(status: String, connectedSince: Long = 0L) {
        actualStatus = status
        actualConnectedSince = connectedSince
    }

    internal fun localSnapshot(): Snapshot =
        if (PhantomVpnService.isTunnelActive(actualStatus) &&
            !ActiveVpnSubscriptionProxy.hasRoute()) {
            Snapshot(PhantomVpnService.STATUS_DISCONNECTED, 0L)
        } else Snapshot(actualStatus, actualConnectedSince)

    private fun uri(context: Context) = Uri.parse("content://${context.packageName}.vpn-runtime")

    fun snapshot(context: Context): Snapshot = runCatching {
        val data = requireNotNull(context.contentResolver.call(uri(context), "state", null, null))
        Snapshot(
            data.getString("status") ?: PhantomVpnService.STATUS_DISCONNECTED,
            data.getLong("since", 0L),
            profileDigest = data.getString("digest"),
        )
    }.getOrDefault(Snapshot(PhantomVpnService.STATUS_DISCONNECTED, 0L, available = false))

    fun routeForDigest(context: Context, digest: String): ActiveVpnSubscriptionProxy.Route? {
        if (digest.length != 64) return null
        return runCatching {
            val data = context.contentResolver.call(uri(context), "route", digest, null) ?: return null
            val port = data.getInt("port", -1)
            val password = data.getString("password").orEmpty()
            val profileDigest = data.getString("digest").orEmpty()
            if (port !in 1024..65535 || password.isBlank() || profileDigest != digest) null
            else ActiveVpnSubscriptionProxy.Route(port, password, profileDigest)
        }.getOrNull()
    }

    fun isActive(context: Context, route: ActiveVpnSubscriptionProxy.Route): Boolean =
        runCatching {
            val request = Bundle().apply {
                putInt("port", route.port)
                putString("password", route.password)
                putString("digest", route.profileDigest)
            }
            context.contentResolver.call(uri(context), "isActive", null, request)
                ?.getBoolean("active", false) == true
        }.getOrDefault(false)
}

class VpnRuntimeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "state" -> {
            val s = VpnRuntimeBridge.localSnapshot()
            Bundle().apply {
                putString("status", s.status)
                putLong("since", s.connectedSince)
                putString("digest", ActiveVpnSubscriptionProxy.currentDigest())
            }
        }
        "route" -> {
            val route = arg?.let(ActiveVpnSubscriptionProxy::forDigest)
            Bundle().apply {
                if (route != null) {
                    putInt("port", route.port)
                    putString("password", route.password)
                    putString("digest", route.profileDigest)
                }
            }
        }
        "isActive" -> {
            val route = extras?.let {
                ActiveVpnSubscriptionProxy.Route(it.getInt("port", -1),
                    it.getString("password").orEmpty(),
                    it.getString("digest").orEmpty())
            }
            Bundle().apply { putBoolean("active",
                route != null && ActiveVpnSubscriptionProxy.isActive(route)) }
        }
        else -> null
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?): Int = 0
}
