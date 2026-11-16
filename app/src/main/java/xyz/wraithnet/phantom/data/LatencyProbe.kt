package xyz.wraithnet.phantom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import xyz.wraithnet.phantom.service.XrayEnvironment

/** Bounded on-demand checks; defaults to TCP to avoid locking Xray for each server. */
object LatencyProbe {
    const val TCP = "tcp"
    const val PROXY = "proxy"
    private val tcpExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "phantom-tcp-ping").apply { isDaemon = true }
    }
    private val proxyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phantom-proxy-ping").apply { isDaemon = true }
    }

    // Always use the fast, bounded TCP check. Previous PROXY preferences no longer override it.
    fun mode(context: Context): String = TCP

    fun timeoutMs(context: Context): Int =
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            .getInt("ping_timeout_seconds", 4).coerceIn(2, 12) * 1000

    fun configure(context: Context, mode: String, seconds: Int) {
        require(mode in setOf(TCP, PROXY))
        require(seconds in 2..12)
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE).edit()
            .putString("ping_mode", mode).putInt("ping_timeout_seconds", seconds).apply()
    }

    internal fun endpoint(server: ServerProfile): Pair<String, Int>? {
        return runCatching {
            val raw = server.raw.trim()
            if (raw.startsWith("{")) {
                val objectRoot = JSONObject(raw)
                val list = objectRoot.optJSONArray("outbounds")
                val outbound = (0 until (list?.length() ?: 0))
                    .mapNotNull { list?.optJSONObject(it) }
                    .firstOrNull { it.optString("protocol") in setOf(
                        "vless", "vmess", "trojan", "shadowsocks", "hysteria"
                    ) } ?: objectRoot
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

    suspend fun measure(context: Context, server: ServerProfile): Long = withContext(Dispatchers.IO) {
        val timeout = timeoutMs(context)
        if (mode(context) == TCP) {
            if (server.scheme.lowercase() in setOf("hysteria", "hysteria2", "hy2", "tuic")) {
                // UDP servers cannot be tested honestly with a TCP handshake.
                return@withContext -1L
            }
            val (host, port) = endpoint(server) ?: return@withContext -1L
            // Bound the full DNS + socket handshake, not only connect().
            val task = tcpExecutor.submit<Long> {
                val begin = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin).coerceAtLeast(1L)
            }
            return@withContext try {
                task.get(timeout.toLong() + 500L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                task.cancel(true)
                -1L
            }
        }
        val job = proxyExecutor.submit<Long> {
            XrayEnvironment.initialize(context)
            val config = FragmentationOptions.load(context).apply(XrayConfigBuilder.build(server))
            Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
        }
        return@withContext try {
            job.get(timeout.toLong(), TimeUnit.MILLISECONDS).takeIf { it > 0L } ?: -1L
        } catch (_: Exception) {
            job.cancel(true)
            -1L
        }
    }
}
