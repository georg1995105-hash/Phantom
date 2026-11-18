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

/** Measure a TCP handshake, a QUIC version response, or HTTPS through the active VPN. */
object LatencyProbe {
    const val TCP = "tcp"
    const val PROXY = "proxy"

    data class PingResult(val milliseconds: Long, val source: String) {
        val label: String get() = "$source · $milliseconds мс"
    }

    private val probeExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "phantom-latency").apply { isDaemon = true }
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
        if (!prefs.getBoolean("vpn_running", false) || prefs.getString("vpn_last_profile", null) != server.raw ||
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
        if (raw.startsWith("vmess://", ignoreCase = true)) return runCatching {
            val vmess = JSONObject(ShareLinkCodec.base64Text(raw.substringAfter("://").substringBefore('#')))
            val network = vmess.optString("net").lowercase()
            network in setOf("kcp", "mkcp", "quic", "h3") ||
                network in setOf("xhttp", "splithttp", "http") && isH3(vmess.optString("alpn"))
        }.getOrDefault(true)
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
                    network in setOf("hysteria", "hysteria2", "quic", "h3", "mkcp", "kcp") ||
                    (network in setOf("xhttp", "splithttp", "http") && h3)
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
            network in setOf("kcp", "mkcp", "quic", "h3") || network in setOf("xhttp", "splithttp", "http") && isH3(query["alpn"].orEmpty())
        }.getOrDefault(false)
    }

    // A mixed/balanced or masked profile cannot be attributed to one plain QUIC peer.
    private fun canProbeQuic(server: ServerProfile): Boolean = runCatching {
        val raw = server.raw.trim()
        if (raw.startsWith("{")) {
            val root = JSONObject(raw)
            val list = root.optJSONArray("outbounds")
            val candidates = if (list == null) listOf(root) else
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter {
                    it.optString("protocol") !in setOf("freedom", "blackhole", "dns")
                }
            val proxy = candidates.singleOrNull() ?: return@runCatching false
            val stream = proxy.optJSONObject("streamSettings")
            stream?.optJSONObject("finalmask")?.optJSONArray("udp") == null &&
                stream?.optString("network") !in setOf("kcp", "mkcp")
        } else if (raw.startsWith("vmess://", ignoreCase = true)) {
            val vmess = JSONObject(ShareLinkCodec.base64Text(raw.substringAfter("://").substringBefore('#')))
            vmess.optString("net").lowercase() !in setOf("kcp", "mkcp")
        } else {
            val query = URI(raw).rawQuery.orEmpty().lowercase()
            !Regex("(?:^|&)(?:obfs=|(?:type|transport|network)=(?:kcp|mkcp))").containsMatchIn(query)
        }
    }.getOrDefault(false)

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

    /**
     * The Android captive-portal validation server can be blocked even while a
     * VPN server is reachable. A real socket is the source of truth here.
     * Only use physical networks; prefer validated links when available.
     */
    internal fun physicalNetworkRank(caps: NetworkCapabilities): Int? = physicalNetworkRank(
        notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
    )

    /** Pure counterpart for deterministic ranking tests without constructing Android system capabilities. */
    internal fun physicalNetworkRank(
        notVpn: Boolean,
        internet: Boolean,
        validated: Boolean,
        wifi: Boolean,
        cellular: Boolean,
        ethernet: Boolean,
    ): Int? {
        if (!notVpn || !internet) return null
        val validation = if (validated) 0 else 10
        val transport = when {
            wifi -> 0
            cellular -> 1
            ethernet -> 2
            else -> 3
        }
        return validation + transport
    }

    /** Pin DNS and socket to the SAME physical network, bypassing the VPN. */
    private fun physicalNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val priority = physicalNetworkRank(caps) ?: return@mapNotNull null
            priority to network
        }.minByOrNull { it.first }?.second
    }

    suspend fun measure(context: Context, server: ServerProfile): Long {
        val verified = lastVerifiedTunnelLatency(context, server)
        return if (verified > 0) verified else measureResult(context, server)?.milliseconds ?: -1L
    }

    /** Does not select a profile, start a VPN, or invoke a second native core. */
    suspend fun measureResult(context: Context, server: ServerProfile): PingResult? =
        withContext(Dispatchers.IO) {
            val udp = needsTunnel(server)
            if (udp && !canProbeQuic(server)) return@withContext null
            val (host, port) = endpoint(server) ?: return@withContext null
            val timeout = timeoutMs(context)
            val task = probeExecutor.submit<Long> {
                // Loopback is used by offline instrumentation tests only.
                val local = host.equals("localhost", true) || host == "127.0.0.1" ||
                    host == "::1" || host == "[::1]"
                val physical = if (local) null else physicalNetwork(context)
                if (!local && physical == null) return@submit -1L
                val addresses = if (local) InetAddress.getAllByName(host)
                    else physical!!.getAllByName(host)
                val begin = System.nanoTime()
                for (address in addresses) {
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                    val remaining = (timeout.toLong() - elapsed).toInt()
                    if (remaining <= 0) break
                    try {
                        val attempt = System.nanoTime()
                        if (udp) {
                            if (!QuicRtt.measure(physical, address, port, remaining)) continue
                        } else (if (local) Socket() else physical!!.socketFactory.createSocket()).use {
                            it.connect(InetSocketAddress(address, port), remaining)
                        }
                        return@submit TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attempt)
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
            if (millis > 0L) PingResult(millis, if (udp) "QUIC" else "TCP") else null
        }
}
