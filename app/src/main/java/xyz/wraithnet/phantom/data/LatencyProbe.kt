package xyz.wraithnet.phantom.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.service.ProfileLatencyClient

/** One certificate-checked HTTP round trip through the selected profile. */
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

    suspend fun measureResult(context: Context, server: ServerProfile): Long? =
        measureResult(context, server, TARGET, timeoutMs(context))

    /** Enables a controlled origin in emulator tests without changing production TLS or routing. */
    internal suspend fun measureResult(
        context: Context, server: ServerProfile, target: String, timeout: Int,
    ): Long? {
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
