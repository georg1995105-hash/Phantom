package xyz.wraithnet.phantom.data

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.service.ProfileLatencyClient
import xyz.wraithnet.phantom.service.ProfileTiming
import xyz.wraithnet.phantom.service.TcpPing

/** Server list RTTs use physical TCP sockets except TUIC, which requires an actual QUIC tunnel. */
object LatencyProbe {
    private val tcpSlots = Semaphore(12)
    private const val AUTO_TUIC_PING = "auto_tuic_ping"

    fun autoTuicEnabled(context: Context): Boolean =
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            .getBoolean(AUTO_TUIC_PING, false)

    fun setAutoTuicEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_TUIC_PING, value).apply()
    }


    fun timeoutMs(context: Context): Int =
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            .getInt("ping_timeout_seconds", 4).coerceIn(2, 12) * 1000

    fun configure(context: Context, seconds: Int) {
        require(seconds in 2..12)
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE).edit()
            .putInt("ping_timeout_seconds", seconds).apply()
    }

    internal suspend fun measureTcp(context: Context, server: ServerProfile): Long? {
        // TUIC has no TCP handshake to measure. Test a real HTTPS roundtrip
        // through the isolated libbox engine; do not change the old TCP ping
        // path for VLESS, Hysteria2, AnyTLS or provider balancers.
        if (ProtocolSupport.usesNativeLatency(server.scheme)) {
            return checkTraffic(context, server, "https://www.gstatic.com/generate_204",
                timeoutMs(context).coerceAtLeast(6_000))
        }
        val peers = withContext(Dispatchers.Default) {
            runCatching { TcpEndpoint.listEntries(server) }.getOrDefault(emptyList())
        }
        val timeout = timeoutMs(context)
        suspend fun connect(peer: TcpEndpoint) = tcpSlots.withPermit { TcpPing.measure(context, peer, timeout) }
        if (peers.size <= 1) return peers.firstOrNull()?.let { connect(it) }
        // Race the provider's actual balancer members. Report the responding socket's RTT.
        return coroutineScope {
            val results = Channel<Long?>(Channel.UNLIMITED)
            val next = AtomicInteger()
            val workers = List(minOf(3, peers.size)) {
                launch {
                    while (true) {
                        val peer = peers.getOrNull(next.getAndIncrement()) ?: break
                        val elapsed = connect(peer)
                        results.send(elapsed)
                        if (elapsed != null) break
                    }
                }
            }
            try {
                repeat(peers.size) { results.receive()?.let { return@coroutineScope it } }
                null
            } finally {
                workers.forEach { it.cancel() }
            }
        }
    }

    /** A real response through the configured core, including its UDP transport or routing graph. */
    internal suspend fun checkTraffic(
        context: Context, server: ServerProfile, target: String, timeout: Int,
    ): Long? = probe(context, server, target, timeout)?.milliseconds

    private suspend fun probe(context: Context, server: ServerProfile, target: String, timeout: Int): ProfileTiming? {
        if (!ProtocolSupport.isSupported(server.scheme)) return null
        return try {
            val singBox = SingBoxBridgeConfig.requiresSingBox(server)
            val payload = if (singBox) server.raw else withContext(Dispatchers.IO) {
                VpnDnsSettings.load(context).applyToXray(
                    FragmentationOptions.load(context).apply(XrayConfigBuilder.build(server))
                )
            }
            ProfileLatencyClient.measure(context, payload, singBox, target, timeout)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
