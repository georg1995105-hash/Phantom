package xyz.wraithnet.phantom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import xyz.wraithnet.phantom.service.XrayEnvironment

/** On-demand outbound HTTP latency. Never display fabricated values. */
object LatencyProbe {
    suspend fun measure(context: Context, server: ServerProfile): Long = withContext(Dispatchers.IO) {
        XrayEnvironment.initialize(context)
        val config = FragmentationOptions.load(context).apply(XrayConfigBuilder.build(server))
        Libv2ray.measureOutboundDelay(config, "https://cp.cloudflare.com/generate_204")
            .takeIf { it >= 0L } ?: -1L
    }
}
