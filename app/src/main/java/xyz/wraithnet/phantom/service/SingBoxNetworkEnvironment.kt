package xyz.wraithnet.phantom.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.lang.reflect.Proxy
import java.net.NetworkInterface
import java.net.InetAddress
import java.net.Inet4Address
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
internal class SingBoxNetworkEnvironment(
    context: Context,
    private val postToWorker: (() -> Unit) -> Boolean,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callbacks = ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    /**
     * Resolve the TUIC/AnyTLS server using Android's physical network, bypassing
     * any already active VPN. The original domain remains the TLS SNI.
     * sing-box 1.14 requires a resolver when dialing outbound hostnames; an IP
     * literal avoids using the incomplete app-provided DNS transport.
     */
    fun resolveOutboundAddress(raw: String, pinned: Network? = null): String {
        val host = xyz.wraithnet.phantom.data.ShareLinkCodec.endpointUri(raw).host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }
            ?: error("Не указан адрес TUIC/AnyTLS")
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
            return host.substringBefore('%')
        }
        val network = pinned ?: connectivity.physicalNetwork()
            ?: error("Нет доступной физической сети для DNS TUIC/AnyTLS")
        val addresses = try {
            network.getAllByName(host).toList()
        } catch (e: Exception) {
            throw IllegalStateException("DNS физической сети не определил сервер TUIC/AnyTLS")
        }
        val resolved = addresses.filterIsInstance<Inet4Address>().firstOrNull()
            ?: addresses.firstOrNull()
            ?: error("DNS вернул пустой список IP сервера")
        return resolved.hostAddress.substringBefore('%')
    }

    private fun notifyDefault(listener: InterfaceUpdateListener) {
        val network = connectivity.physicalNetwork()
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val name = network?.let(connectivity::getLinkProperties)?.interfaceName
        val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
            ?: -1
        val costly = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val constrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == false
        listener.updateDefaultInterface(name ?: "", index, costly, constrained)
    }

    fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (callbacks.containsKey(listener)) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun scheduleUpdate() {
                // Android immediately publishes current networks on ConnectivityThread.
                // Keep these JNI calls out of native startup and service teardown.
                postToWorker {
                    if (callbacks[listener] === this) runCatching { notifyDefault(listener) }
                }
            }

            override fun onAvailable(network: Network) = scheduleUpdate()
            override fun onLost(network: Network) = scheduleUpdate()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleUpdate()
            override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = scheduleUpdate()
        }
        callbacks[listener] = callback
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            // libbox asks for the first interface before startOrReloadService returns.
            notifyDefault(listener)
        } catch (e: Exception) {
            callbacks.remove(listener)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            throw IllegalStateException("Android не запустил наблюдение за физической сетью", e)
        }
    }

    fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
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
