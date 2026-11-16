package xyz.wraithnet.phantom.data

import android.content.Context
import go.Seq
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray

/** On-demand outbound HTTP latency. Never display fabricated values. */
object LatencyProbe {
    suspend fun measure(context: Context, server: ServerProfile): Long = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, "xray-assets").apply { mkdirs() }
        for (asset in listOf("geoip.dat", "geosite.dat")) {
            val target = File(dir, asset)
            if (!target.exists() || target.length() < 1024) {
                val tmp = File(dir, "$asset.tmp")
                appContext.assets.open(asset).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                require(tmp.renameTo(target)) { "Не удалось подготовить базу $asset" }
            }
        }
        Seq.setContext(appContext)
        Libv2ray.initCoreEnv(dir.absolutePath, "phantom-" + appContext.packageName)
        val config = XrayConfigBuilder.build(server)
        Libv2ray.measureOutboundDelay(config, "https://cp.cloudflare.com/generate_204")
            .takeIf { it >= 0L } ?: -1L
    }
}
