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
 * Runs ONLY in :singbox Android process, never in Xray VPN process.
 * Xray still owns the single Android TUN; a local SOCKS proxy bridges TCP/UDP.
 * JNI packages remain isolated at build time to prevent duplicate Dex classes.
 *
 * Never prints raw profiles: the share link includes authentication credentials.
 */
internal class SingBoxNativeBackend(private val vpn: VpnService) {
    @Volatile private var server: CommandServer? = null
    val isRunning: Boolean get() = server != null
    private val networkEnvironment = SingBoxNetworkEnvironment(vpn)

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
                    appVersion = "20"
                    appMarketingVersion = "0.2.18"
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
                    // Outbound QUIC/TLS sockets must bypass the VPN TUN itself.
                    val fd = args?.get(0) as? Int
                        ?: error("libbox: нет файлового дескриптора для защиты")
                    check(vpn.protect(fd)) { "Android не разрешил обход VPN для исходящего сокета" }
                    null
                }
                "startDefaultInterfaceMonitor" -> {
                    networkEnvironment.startDefaultInterfaceMonitor(
                        args?.firstOrNull() ?: error("libbox: нет слушателя смены сети"))
                    null
                }
                "closeDefaultInterfaceMonitor" -> {
                    args?.firstOrNull()?.let(networkEnvironment::closeDefaultInterfaceMonitor)
                    null
                }
                "openTun" -> error("sing-box не создаёт второй VPN-интерфейс")
                "getInterfaces" -> networkEnvironment.getInterfaces()
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
        // Never announce a usable SOCKS listener with an empty physical network table.
        val available = networkEnvironment.getInterfaces()
        val hasNext = available.javaClass.methods.first {
            it.name.equals("hasNext", ignoreCase = true)
        }.invoke(available) as Boolean
        check(hasNext) { "Android не обнаружил физическую сеть для TUIC/AnyTLS" }
        val port = ServerSocket(0).use { it.localPort }
        val resolvedIp = networkEnvironment.resolveOutboundAddress(raw)
        val config = SingBoxBridgeConfig.fromShareLink(raw, port, resolvedIp)
        val current = CommandServer(callbacks(), platform())
        try {
            current.start()
            current.startOrReloadService(config, OverrideOptions())
            server = current
            return port
        } catch (e: Exception) {
            runCatching { current.closeService() }
            runCatching { current.close() }
            networkEnvironment.close()
            throw IllegalStateException("Не удалось запустить TUIC/AnyTLS: " +
                (e.message ?: "ошибка ядра"), e)
        }
    }

    fun stop() {
        val current = server ?: return
        server = null
        runCatching { current.closeService() }
        runCatching { current.close() }
        networkEnvironment.close()
    }
}
