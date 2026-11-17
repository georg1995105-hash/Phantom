package xyz.wraithnet.phantom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.AppRouting
import xyz.wraithnet.phantom.data.FragmentationOptions
import xyz.wraithnet.phantom.data.TrafficRates
import xyz.wraithnet.phantom.data.VpnDnsSettings
import xyz.wraithnet.phantom.data.VpnEventLog
import xyz.wraithnet.phantom.data.XrayConfigBuilder
import xyz.wraithnet.phantom.data.SingBoxBridgeConfig
import xyz.wraithnet.phantom.R
import xyz.wraithnet.phantom.MainActivity

class PhantomVpnService : VpnService() {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var prefs: SharedPreferences
    private var vpnInterface: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var singBoxBridge: SingBoxBridge? = null
    private var singBoxSession: Any? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var trafficJob: Job? = null
    private var handoverJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastPhysicalRoute: String? = null

    @Volatile private var running = false
    @Volatile private var state = STATUS_DISCONNECTED
    @Volatile private var revokedByAndroid = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Phantom VPN"))
        observePhysicalNetworks()
    }

    /**
     * Observe Wi-Fi and cellular availability, ignoring the Phantom VPN virtual
     * interface. Re-establish stale outbound sockets only on a genuine change
     * of the validated physical route.
     */
    private fun physicalRoute(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@mapNotNull null
            }
            val priority = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                else -> 3
            }
            priority to network.toString()
        }.sortedBy { it.first }.firstOrNull()?.second
    }

    private fun scheduleNetworkCheck() {
        handoverJob?.cancel()
        handoverJob = ioScope.launch {
            delay(1600L)
            mutex.withLock {
                if (revokedByAndroid || !prefs.getBoolean("vpn_requested", false)) return@withLock
                val route = runCatching { physicalRoute() }.getOrNull() ?: return@withLock
                if (route == lastPhysicalRoute) return@withLock
                val previous = lastPhysicalRoute
                val profile = prefs.getString("vpn_last_profile", "").orEmpty()
                if (profile.isBlank()) return@withLock
                // First boot or a real Wi-Fi/mobile handover; do not restart on
                // unrelated network callbacks or repeated capability updates.
                if (previous != null || !running) {
                    startLocked(profile)
                }
            }
        }
    }

    private fun observePhysicalNetworks() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkCheck()
            override fun onLost(network: Network) = scheduleNetworkCheck()
            override fun onCapabilitiesChanged(
                network: Network, capabilities: NetworkCapabilities
            ) = scheduleNetworkCheck()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("vpn_requested", false).apply()
                handoverJob?.cancel()
                ioScope.launch {
                    mutex.withLock { stopLocked() }
                    stopSelf()
                }
            }
            ACTION_QUERY -> sendStatus(state)
            ACTION_TRAFFIC_ON -> ioScope.launch {
                mutex.withLock { if (running) startTrafficReports() }
            }
            ACTION_TRAFFIC_OFF -> ioScope.launch {
                mutex.withLock {
                    trafficJob?.cancel()
                    trafficJob = null
                }
            }
            ACTION_RELOAD -> ioScope.launch { mutex.withLock {
                startLocked(prefs.getString("vpn_last_profile", "").orEmpty())
            } }
            ACTION_KEEP_ALIVE -> ioScope.launch {
                mutex.withLock { configureWakeLock() }
            }
            ACTION_START -> {
                val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                if (profile.isNotBlank()) {
                    prefs.edit().putBoolean("vpn_requested", true).apply()
                    ioScope.launch { mutex.withLock { startLocked(profile) } }
                } else setState(STATUS_ERROR, "Сначала выберите сервер")
            }
            ACTION_BOOT_START -> {
                val last = prefs.getString("vpn_last_profile", "").orEmpty()
                if (last.isNotBlank() && VpnService.prepare(this) == null) {
                    prefs.edit().putBoolean("vpn_requested", true).apply()
                    setState(STATUS_STARTING)
                    updateNotification("Ожидание сети после включения телефона")
                    scheduleNetworkCheck()
                } else stopSelf()
            }
            else -> {
                val last = prefs.getString("vpn_last_profile", "").orEmpty()
                val alwaysOn = Build.VERSION.SDK_INT >= 29 && isAlwaysOn
                if (last.isNotBlank() && (alwaysOn ||
                    prefs.getBoolean("vpn_requested", false))) {
                    prefs.edit().putBoolean("vpn_requested", true).apply()
                    scheduleNetworkCheck()
                } else {
                    stopSelf()
                }
            }
        }
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private fun startLocked(raw: String) {
        if (revokedByAndroid) return
        stopLocked(false)
        setState(STATUS_STARTING)
        try {
            require(raw.isNotBlank()) { "Профиль сервера пуст" }
            val scheme = if (raw.trimStart().startsWith("{")) "xray"
                else raw.substringBefore(':').lowercase()
            val dns = VpnDnsSettings.load(this)
            check(dns.validate() == null) { dns.validate() ?: "Неверные DNS" }
            val profile = ServerProfile("runtime", "runtime", scheme, raw = raw)
            val baseConfig = if (SingBoxBridgeConfig.requiresSingBox(profile)) {
                // sing-box handles the native TUIC/AnyTLS transport, while Xray
                // keeps Android TUN and all existing per-app VPN routing.
                val session = Any()
                singBoxSession = session
                val engine = SingBoxBridge(this) {
                    ioScope.launch {
                        mutex.withLock {
                            if (singBoxSession === session) {
                                stopLocked(false)
                                setState(STATUS_ERROR, "Процесс sing-box неожиданно завершился")
                                updateNotification("Ошибка движка VPN")
                            }
                        }
                    }
                }
                singBoxBridge = engine
                val socksPort = engine.start(raw)
                val upstream = SocksHttpsProbe.check(socksPort)
                check(upstream.delayMs > 0L) {
                    "TUIC/AnyTLS не передаёт трафик: " + upstream.failure
                }
                XrayConfigBuilder.buildSocksBridge(socksPort)
            } else {
                XrayConfigBuilder.build(profile)
            }
            val config = dns.applyToXray(FragmentationOptions.load(this).apply(baseConfig))
            // The VPN's own sockets must not recursively enter the VPN TUN.
            // The app is intentionally excluded; user apps still route via VPN.
            val tun = Builder()
                .setSession("Phantom")
                .setMtu(1400)
                .addAddress("10.111.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1111::2", 64)
                .addRoute("::", 0)
                .also { builder ->
                    dns.addresses().forEach { builder.addDnsServer(it) }
                    AppRouting.apply(this, builder)
                }
                .establish() ?: error("Android не создал VPN-интерфейс")
            vpnInterface = tun

            XrayEnvironment.initialize(applicationContext)
            val active = Libv2ray.newCoreController(callback)
            controller = active
            active.startLoop(config, tun.fd)

            // Core startup alone is not proof of Internet access.
            // Measure via the configured Xray outbound, not device networking.
            var verifiedDelay = -1L
            for (endpoint in listOf(
                "https://www.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
            )) {
                val value = runCatching { active.measureDelay(endpoint) }.getOrDefault(-1L)
                if (value > 0L) {
                    verifiedDelay = value
                    break
                }
            }
            require(verifiedDelay > 0L) {
                if (SingBoxBridgeConfig.requiresSingBox(profile))
                    "sing-box передаёт HTTPS, но Xray не пропускает его через VPN-туннель."
                else "Туннель запущен, но интернет через сервер не отвечает. Выберите другой сервер."
            }

            check(!revokedByAndroid) { "Android переключил VPN на другое приложение" }
            running = true
            prefs.edit().putString("vpn_last_profile", raw)
                .putBoolean("vpn_running", true)
                .putLong("vpn_last_latency_ms", verifiedDelay)
                .putInt("vpn_last_latency_profile_hash", raw.hashCode())
                .putLong("vpn_connected_since_elapsed", SystemClock.elapsedRealtime()).apply()
            configureWakeLock()
            lastPhysicalRoute = runCatching { physicalRoute() }.getOrNull()
            setState(STATUS_CONNECTED)
            updateNotification("VPN подключён")
            if (prefs.getBoolean("ui_visible", false)) startTrafficReports()
        } catch (e: Exception) {
            stopLocked(false)
            if (revokedByAndroid) setState(STATUS_DISCONNECTED)
            else setState(STATUS_ERROR, e.message ?: "Не удалось подключиться")
            updateNotification("Ошибка подключения")
        }
    }

    private fun stopLocked(report: Boolean = true) {
        running = false
        trafficJob?.cancel()
        trafficJob = null
        releaseWakeLock()
        runCatching { controller?.stopLoop() }
        controller = null
        singBoxSession = null
        runCatching { singBoxBridge?.stop() }
        singBoxBridge = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        prefs.edit().putBoolean("vpn_running", false)
            .remove("vpn_last_latency_ms").remove("vpn_last_latency_profile_hash")
            .putLong("vpn_connected_since_elapsed", 0L).apply()
        if (report) {
            setState(STATUS_DISCONNECTED)
            updateNotification("VPN отключён")
        }
    }

    /** Only the running Xray core is queried; no root access, server API or device-wide counters. */
    private fun startTrafficReports() {
        trafficJob?.cancel()
        trafficJob = ioScope.launch {
            var lastAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1500L)
                mutex.withLock {
                    if (!running) return@withLock
                    val report = runCatching {
                        controller?.queryAllOutboundTrafficStats().orEmpty()
                    }.getOrDefault("")
                    val now = SystemClock.elapsedRealtime()
                    val rate = TrafficRates.fromXrayReport(report, now - lastAt)
                    lastAt = now
                    sendBroadcast(
                        Intent(ACTION_TRAFFIC).setPackage(packageName)
                            .putExtra(EXTRA_DOWNLOAD_BPS, rate?.downloadBytesPerSecond ?: -1L)
                            .putExtra(EXTRA_UPLOAD_BPS, rate?.uploadBytesPerSecond ?: -1L)
                    )
                }
            }
        }
    }

    private fun configureWakeLock() {
        releaseWakeLock()
        if (!running || !prefs.getBoolean("keep_active", false)) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:vpn-keep-active").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String): Long {
            // The synchronous start/verification path determines actual UI state.
            return 0
        }
    }

    private fun setState(next: String, error: String = "") {
        // Persist and publish the real service state BEFORE asking SystemUI to redraw.
        state = next
        prefs.edit().putString("vpn_status", next).apply()
        VpnEventLog.record(applicationContext, next, error)
        sendStatus(next, error)
        PhantomQuickTileService.notifyStateChanged(applicationContext)
    }

    private fun sendStatus(status: String, error: String = "") {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, status).putExtra(EXTRA_ERROR, error)
                .putExtra(EXTRA_CONNECTED_SINCE,
                    if (status == STATUS_CONNECTED) prefs.getLong("vpn_connected_since_elapsed", 0L) else 0L)
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phantom VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 11,
            Intent(this, PhantomVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phantom VPN")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_phantom)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (running) {
            builder.addAction(Notification.Action.Builder(
                R.drawable.ic_stat_phantom, "Отключить", stop
            ).build())
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onRevoke() {
        // Android invokes this when another VPN (such as INCY) takes ownership.
        // Publish the revoked state *before* super.onRevoke() stops the service:
        // deferred coroutines can be cancelled by onDestroy before they run.
        revokedByAndroid = true
        prefs.edit().putBoolean("vpn_requested", false).apply()
        handoverJob?.cancel()
        running = false
        prefs.edit().putBoolean("vpn_running", false)
            .putLong("vpn_connected_since_elapsed", 0L).apply()
        setState(STATUS_DISCONNECTED)
        super.onRevoke()
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) {
            running = false
            prefs.edit().putBoolean("vpn_running", false)
                .putString("vpn_status", STATUS_DISCONNECTED)
                .putLong("vpn_connected_since_elapsed", 0L).apply()
            PhantomQuickTileService.notifyStateChanged(applicationContext)
            sendStatus(STATUS_DISCONNECTED)
        }
        handoverJob?.cancel()
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(callback)
            }
        }
        trafficJob?.cancel()
        releaseWakeLock()
        runCatching { controller?.stopLoop() }
        singBoxSession = null
        runCatching { singBoxBridge?.stop() }
        runCatching { vpnInterface?.close() }
        ioScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_START = "xyz.wraithnet.phantom.START_VPN"
        const val ACTION_BOOT_START = "xyz.wraithnet.phantom.BOOT_START_VPN"
        const val ACTION_STOP = "xyz.wraithnet.phantom.STOP_VPN"
        const val ACTION_QUERY = "xyz.wraithnet.phantom.QUERY_VPN"
        const val ACTION_KEEP_ALIVE = "xyz.wraithnet.phantom.KEEP_ALIVE"
        const val ACTION_RELOAD = "xyz.wraithnet.phantom.RELOAD_VPN"
        const val ACTION_STATUS = "xyz.wraithnet.phantom.VPN_STATUS"
        const val ACTION_TRAFFIC = "xyz.wraithnet.phantom.VPN_TRAFFIC"
        const val ACTION_TRAFFIC_ON = "xyz.wraithnet.phantom.TRAFFIC_ON"
        const val ACTION_TRAFFIC_OFF = "xyz.wraithnet.phantom.TRAFFIC_OFF"
        const val EXTRA_DOWNLOAD_BPS = "download_bytes_per_second"
        const val EXTRA_UPLOAD_BPS = "upload_bytes_per_second"
        const val EXTRA_CONNECTED_SINCE = "connected_since_elapsed"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_STARTING = "starting"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_ERROR = "error"
        private const val CHANNEL_ID = "phantom_vpn"
        private const val NOTIFICATION_ID = 1001
    }
}
