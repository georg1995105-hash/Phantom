package xyz.wraithnet.phantom.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.lang.reflect.Proxy
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Android network adapter for the sing-box process.
 *
 * libbox requests platform network interfaces and default-interface updates.
 * Returning an empty iterator and silently swallowing start-monitor callbacks
 * makes native outbound routing unreliable, particularly after VPN creation.
 *
 * This adapter publishes actual Android network data while excluding the VPN
 * TUN itself from the default upstream selection.
 */
internal class SingBoxNetworkEnvironment(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callbacks = ConcurrentHashMap<Any, ConnectivityManager.NetworkCallback>()

    private fun physicalNetwork(): Network? {
        return connectivity.allNetworks
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) return@mapNotNull null
                val link = connectivity.getLinkProperties(network)
                if (link?.interfaceName.isNullOrBlank()) return@mapNotNull null
                val rank =
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0) +
                    (if (network == connectivity.activeNetwork) 80 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 20 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 15 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 10 else 0)
                Triple(network, caps, rank)
            }.maxByOrNull { it.third }?.first
    }

    private fun notifyDefault(listener: Any) {
        val network = physicalNetwork()
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val name = network?.let(connectivity::getLinkProperties)?.interfaceName
        val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
            ?: -1
        val costly = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val constrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == false
        val method = listener.javaClass.methods.firstOrNull {
            it.name.equals("updateDefaultInterface", ignoreCase = true) &&
                it.parameterCount == 4
        } ?: error("libbox: нет callback обновления сетевого интерфейса")
        method.invoke(listener, name ?: "", index, costly, constrained)
    }

    fun startDefaultInterfaceMonitor(listener: Any) {
        if (callbacks.containsKey(listener)) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { notifyDefault(listener) }
            }
            override fun onLost(network: Network) {
                runCatching { notifyDefault(listener) }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                runCatching { notifyDefault(listener) }
            }
            override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) {
                runCatching { notifyDefault(listener) }
            }
        }
        callbacks[listener] = callback
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            notifyDefault(listener)
        } catch (e: Exception) {
            callbacks.remove(listener)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            throw IllegalStateException("Android не запустил наблюдение за физической сетью", e)
        }
    }

    fun closeDefaultInterfaceMonitor(listener: Any) {
        callbacks.remove(listener)?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    fun close() {
        callbacks.values.forEach { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        callbacks.clear()
    }

    /**
     * Gomobile structs expose setters, while iterator types are Java interfaces.
     * Reflective construction tolerates minor generated naming differences.
     */
    fun getInterfaces(): Any {
        val iteratorType = Class.forName("io.nekohasekai.libbox.NetworkInterfaceIterator")
        val itemType = Class.forName("io.nekohasekai.libbox.NetworkInterface")
        val physicalLinks = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            connectivity.getLinkProperties(network)?.let { link ->
                link.interfaceName?.let { name -> name to (caps to link) }
            }
        }.toMap()
        val items = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { iface -> runCatching { iface.isUp && !iface.isLoopback &&
                !iface.isVirtual && !iface.name.startsWith("tun") &&
                !iface.name.startsWith("wg") }.getOrDefault(false) }
            .map { iface ->
                val item = itemType.getDeclaredConstructor().newInstance()
                put(item, "Name", iface.name)
                put(item, "Index", iface.index)
                put(item, "MTU", iface.mtu)
                put(item, "Flags", 1 or 64) // IFF_UP | IFF_RUNNING
                val addresses = iface.interfaceAddresses.mapNotNull { itf ->
                    val address = itf.address?.hostAddress?.substringBefore('%')
                        ?: return@mapNotNull null
                    val prefix = itf.networkPrefixLength.toInt()
                    "${address}/${prefix}"
                }
                put(item, "Addresses", stringIterator(addresses))
                val (caps, link) = physicalLinks[iface.name] ?: (null to null)
                put(item, "DNSServer", stringIterator(link?.dnsServers?.map { dns ->
                    dns.hostAddress?.substringBefore('%').orEmpty()
                }?.filter { it.isNotBlank() }.orEmpty()))
                put(item, "Gateway", stringIterator(emptyList()))
                val type = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 1
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 2
                    else -> 3
                }
                put(item, "Type", type)
                put(item, "Metered", caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false)
                item
            }
        var position = 0
        return Proxy.newProxyInstance(iteratorType.classLoader, arrayOf(iteratorType)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> position < items.size
                "next" -> items[position++]
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        }
    }

    private fun put(target: Any, field: String, value: Any) {
        val method = target.javaClass.methods.firstOrNull {
            it.name.equals("set$field", ignoreCase = true) && it.parameterCount == 1
        } ?: error("libbox: отсутствует сетевое поле $field")
        method.invoke(target, value)
    }

    private fun stringIterator(values: List<String>): Any {
        val type = Class.forName("io.nekohasekai.libbox.StringIterator")
        var position = 0
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> position < values.size
                "len" -> values.size
                "next" -> values[position++]
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        }
    }
}
