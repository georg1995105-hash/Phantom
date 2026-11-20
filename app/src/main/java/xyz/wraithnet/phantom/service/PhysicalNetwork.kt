package xyz.wraithnet.phantom.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/** Pick the default for this UID, or an accessible underlying network when the default is a VPN. */
@Suppress("DEPRECATION")
internal fun ConnectivityManager.physicalNetwork(): Network? {
    fun usable(network: Network): Boolean = getNetworkCapabilities(network)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    } == true && !getLinkProperties(network)?.interfaceName.isNullOrBlank()

    val active = activeNetwork
    if (active != null) {
        if (usable(active)) return active
        if (Build.VERSION.SDK_INT >= 31) {
            getNetworkCapabilities(active)?.underlyingNetworks?.firstOrNull(::usable)?.let { return it }
        }
    }
    return allNetworks.filter(::usable).maxByOrNull { network ->
        val caps = getNetworkCapabilities(network) ?: return@maxByOrNull 0
        (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 8 else 0) +
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                else -> 1
            }
    }
}
