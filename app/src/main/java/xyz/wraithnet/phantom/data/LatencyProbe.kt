package xyz.wraithnet.phantom.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Non-invasive checks: TCP connect for TCP profiles, verified active VPN latency for UDP/H3. */
object LatencyProbe {
    const val TCP = "tcp"
    const val PROXY = "proxy"

    data class PingResult(val milliseconds: Long, val source: String) {
        val label: String get() = "$source · $milliseconds мс"
    }

    private val tcpExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "phantom-tcp-ping").apply { isDaemon = true }
    }

    // This option is retained for compatibility with previous preferences.
    // Measurements never start a second Xray runtime or alter the running VPN.
    fun mode(context: Context): String = TCP

    fun timeoutMs(context: Context): Int =
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            .getInt("ping_timeout_seconds", 4).coerceIn(2, 12) * 1000

    fun configure(context: Context, mode: String, seconds: Int) {
        require(mode in setOf(TCP, PROXY))
        require(seconds in 2..12)
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE).edit()
            .putString("ping_mode", TCP).putInt("ping_timeout_seconds", seconds).apply()
    }

    /** A verified HTTPS request executed through precisely this connected profile. */
    fun lastVerifiedTunnelLatency(context: Context, server: ServerProfile): Long {
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("vpn_running", false) ||
            prefs.getInt("vpn_last_latency_profile_hash", Int.MIN_VALUE) != server.raw.hashCode()
        ) return -1L
        return prefs.getLong("vpn_last_latency_ms", -1L).takeIf { it > 0L } ?: -1L
    }

    private fun isH3(value: String): Boolean =
        value.split(',').any { it.trim().lowercase() in setOf("h3", "http/3") }

    private fun isH3(values: JSONArray?): Boolean =
        (0 until (values?.length() ?: 0)).any { index -> isH3(values?.optString(index).orEmpty()) }

    /**
     * A subscription may wrap Hysteria2 in a full Xray JSON while exposing the scheme "xray".
     * Inspect the real outbound and streamSettings; never infer transport solely from scheme.
     * Mixed JSON with any UDP/H3 proxy is conservatively left unmeasured by TCP.
     */
    internal fun needsTunnel(server: ServerProfile): Boolean {
        if (ProtocolSupport.needsConnectedPing(server.scheme)) return true
        val raw = server.raw.trim()
        if (raw.startsWith("{")) return runCatching {
            val root = JSONObject(raw)
            val outbounds = root.optJSONArray("outbounds")
            val nodes = if (outbounds == null) listOf(root)
                else (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            nodes.any { outbound ->
                val protocol = outbound.optString("protocol")
                    .ifBlank { outbound.optString("type") }.lowercase()
                val stream = outbound.optJSONObject("streamSettings")
                val network = stream?.optString("network")?.lowercase().orEmpty()
                val h3 = isH3(stream?.optJSONObject("tlsSettings")?.optJSONArray("alpn")) ||
                    isH3(stream?.optJSONObject("realitySettings")?.optJSONArray("alpn"))
                protocol in setOf("hysteria", "hysteria2", "hy2", "tuic") ||
                    network in setOf("hysteria", "hysteria2", "quic", "h3") ||
                    (network in setOf("xhttp", "http") && h3)
            }
        }.getOrDefault(true)

        // Share links may also hide XHTTP/H3 under a VLESS or Trojan URI.
        if (!raw.contains("://")) return false
        return runCatching {
            val uri = URI(raw)
            val query = uri.rawQuery.orEmpty().split('&').associate { segment ->
                ShareLinkCodec.percentDecode(segment.substringBefore('=')).lowercase() to
                    ShareLinkCodec.percentDecode(segment.substringAfter('=', ""))
            }
            val network = (query["type"] ?: query["transport"] ?: query["network"]).orEmpty().lowercase()
            network in setOf("xhttp", "http") && isH3(query["alpn"].orEmpty())
        }.getOrDefault(false)
    }

    internal fun endpoint(server: ServerProfile): Pair<String, Int>? {
        return runCatching {
            val raw = server.raw.trim()
            if (raw.startsWith("{")) {
                val root = JSONObject(raw)
                val list = root.optJSONArray("outbounds")
                val outbound = (0 until (list?.length() ?: 0))
                    .mapNotNull { list?.optJSONObject(it) }
                    .firstOrNull { it.optString("protocol").lowercase() in setOf(
                        "vless", "vmess", "trojan", "shadowsocks", "hysteria"
                    ) } ?: root
                val settings = outbound.optJSONObject("settings") ?: return@runCatching null
                val address = settings.optString("address").ifBlank {
                    val arr = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers")
                    arr?.optJSONObject(0)?.optString("address").orEmpty()
                }
                val port = settings.optInt("port").takeIf { it > 0 }
                    ?: (settings.optJSONArray("vnext") ?: settings.optJSONArray("servers"))
                        ?.optJSONObject(0)?.optInt("port")
                    ?: return@runCatching null
                if (address.isBlank() || port !in 1..65535) null else address to port
            } else if (raw.startsWith("vmess://", ignoreCase = true)) {
                val encoded = raw.substringAfter("://").substringBefore("#")
                val vmess = JSONObject(ShareLinkCodec.base64Text(encoded))
                val host = vmess.optString("add").takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                val port = vmess.optString("port").toIntOrNull()
                    ?.takeIf { it in 1..65535 } ?: return@runCatching null
                host to port
            } else if (raw.startsWith("ss://")) {
                val ss = ShareLinkCodec.shadowsocks(raw)
                ss.host to ss.port
            } else {
                val uri = URI(raw)
                val host = uri.host ?: return@runCatching null
                val port = uri.port.takeIf { it in 1..65535 } ?: 443
                host to port
            }
        }.getOrNull()
    }

    /** Avoid routing direct TCP probes through the selected VPN. */
    private fun physicalNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) return@mapNotNull null
            val priority = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                else -> 3
            }
            priority to network
        }.minByOrNull { it.first }?.second
    }

    suspend fun measure(context: Context, server: ServerProfile): Long =
        measureResult(context, server)?.milliseconds ?: -1L

    /** Does not select a profile, start a VPN, or invoke a second native core. */
    suspend fun measureResult(context: Context, server: ServerProfile): PingResult? =
        withContext(Dispatchers.IO) {
            if (needsTunnel(server)) {
                val verified = lastVerifiedTunnelLatency(context, server)
                return@withContext if (verified > 0L) PingResult(verified, "VPN") else null
            }
            val (host, port) = endpoint(server) ?: return@withContext null
            val timeout = timeoutMs(context)
            val task = tcpExecutor.submit<Long> {
                // Loopback is used by offline instrumentation tests only.
                val local = host.equals("localhost", true) || host == "127.0.0.1" ||
                    host == "::1" || host == "[::1]"
                val physical = if (local) null else physicalNetwork(context)
                    ?: return@submit -1L
                val addresses = if (local) InetAddress.getAllByName(host)
                    else physical!!.getAllByName(host)
                val begin = System.nanoTime()
                for (address in addresses) {
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                    val remaining = (timeout.toLong() - elapsed).toInt()
                    if (remaining <= 0) break
                    try {
                        (if (local) Socket() else physical!!.socketFactory.createSocket()).use {
                            it.connect(InetSocketAddress(address, port), remaining)
                        }
                        return@submit TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                            .coerceAtLeast(1L)
                    } catch (_: java.io.IOException) {
                        // Try another address within the same deadline.
                    }
                }
                -1L
            }
            val millis = try {
                task.get(timeout.toLong() + 500L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                task.cancel(true)
                -1L
            }
            if (millis > 0L) PingResult(millis, "TCP") else null
        }
}
