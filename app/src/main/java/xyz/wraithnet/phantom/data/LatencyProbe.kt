package xyz.wraithnet.phantom.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.service.ProfileTiming
import xyz.wraithnet.phantom.service.ProfileLatencyClient
import xyz.wraithnet.phantom.service.TcpPing

enum class LatencyMethod { DIRECT_TCP, PROFILE_ROUTE }

data class LatencySample(val milliseconds: Long, val method: LatencyMethod)

/** Fixed TCP endpoints and routed/UDP profiles have distinct measurements. */
object LatencyProbe {
    const val MAX_PARALLEL_PROBES = 3
    private const val TARGET = "https://cp.cloudflare.com/generate_204"

    fun timeoutMs(context: Context): Int =
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            .getInt("ping_timeout_seconds", 4).coerceIn(2, 12) * 1000

    fun configure(context: Context, seconds: Int) {
        require(seconds in 2..12)
        context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE).edit()
            .putInt("ping_timeout_seconds", seconds).apply()
    }

    internal fun tcpEndpoint(server: ServerProfile): TcpEndpoint? =
        runCatching { TcpEndpoint.independentEntryForProfile(server) }.getOrNull()

    internal suspend fun measureResult(context: Context, server: ServerProfile, endpoint: TcpEndpoint?): Long? =
        measureSample(context, server, endpoint)?.milliseconds

    /** Physical TCP handshake versus end-to-end response through the profile. */
    internal suspend fun measureSample(context: Context, server: ServerProfile, endpoint: TcpEndpoint?): LatencySample? {
        val timeout = timeoutMs(context)
        if (endpoint != null) return TcpPing.measure(context, endpoint, timeout)
            ?.let { LatencySample(it, LatencyMethod.DIRECT_TCP) }
        val routed = probe(context, server, TARGET, timeout) ?: return null
        val chosen = routed.tcpPeer ?: return LatencySample(routed.milliseconds, LatencyMethod.PROFILE_ROUTE)
        return TcpPing.measure(context, chosen, timeout)
            ?.let { LatencySample(it, LatencyMethod.DIRECT_TCP) }
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
