package xyz.wraithnet.phantom.service

import android.content.Context
import android.net.VpnService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.ServerSocket
import xyz.wraithnet.phantom.data.SingBoxBridgeConfig

/**
 * Isolated sing-box SOCKS5 backend; Xray still owns the one Android TUN.
 * Two gomobile runtimes are isolated at build-time; see isolate_libbox_aar.py.
 *
 * Never prints raw profiles: the share link includes authentication credentials.
 */
internal class SingBoxBridge(private val vpn: VpnService) {
    private var server: CommandServer? = null

    companion object {
        private val setupLock = Any()
        @Volatile private var prepared = false

        private fun initialize(context: Context) {
            if (prepared) return
            synchronized(setupLock) {
                if (prepared) return
                val app = context.applicationContext
                val working = app.filesDir.resolve("singbox").apply { mkdirs() }
                val cache = app.cacheDir.resolve("singbox").apply { mkdirs() }
                // The isolated gomobile runtime must have its own Android context.
                sb.Seq.setContext(app)
                Libbox.setup(SetupOptions().apply {
                    basePath = working.absolutePath
                    workingPath = working.absolutePath
                    tempPath = cache.absolutePath
                    debug = false
                    logMaxLines = 200
                    appVersion = "Phantom"
                    appMarketingVersion = "0.2.13"
                })
                prepared = true
            }
        }
    }

    private fun platform(): PlatformInterface {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    vpn.protect(args?.get(0) as Int)
                    null
                }
                "openTun" -> error("sing-box не создаёт второй VPN-интерфейс")
                "getInterfaces" -> {
                    val klass = Class.forName("io.nekohasekai.libbox.NetworkInterfaceIterator")
                    Proxy.newProxyInstance(klass.classLoader, arrayOf(klass)) { _, m, _ ->
                        when (m.name) {
                            "hasNext" -> false
                            "len" -> 0
                            else -> null
                        }
                    }
                }
                "underNetworkExtension" -> false
                "includeAllNetworks" -> false
                "usePlatformBridge" -> false
                "usePlatformShell" -> false
                "usePlatformAutoRedirect" -> false
                "localDNSTransport" -> null
                "readWIFIState" -> null
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }
        return Proxy.newProxyInstance(
            PlatformInterface::class.java.classLoader,
            arrayOf(PlatformInterface::class.java), handler
        ) as PlatformInterface
    }

    private fun callbacks(): CommandServerHandler =
        Proxy.newProxyInstance(
            CommandServerHandler::class.java.classLoader,
            arrayOf(CommandServerHandler::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getSystemProxyStatus" -> SystemProxyStatus().apply {
                    available = false
                    enabled = false
                }
                "connectSSHAgent" -> -1
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as CommandServerHandler

    fun start(raw: String): Int {
        check(server == null) { "sing-box уже запущен" }
        initialize(vpn)
        val port = ServerSocket(0).use { it.localPort }
        val config = SingBoxBridgeConfig.fromShareLink(raw, port)
        val current = CommandServer(callbacks(), platform())
        try {
            current.start()
            current.startOrReloadService(config, OverrideOptions())
            server = current
            return port
        } catch (e: Exception) {
            runCatching { current.closeService() }
            runCatching { current.close() }
            throw IllegalStateException("Не удалось запустить TUIC/AnyTLS: " +
                (e.message ?: "ошибка ядра"), e)
        }
    }

    fun stop() {
        val current = server ?: return
        server = null
        runCatching { current.closeService() }
        runCatching { current.close() }
    }
}
