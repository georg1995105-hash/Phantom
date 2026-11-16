package xyz.wraithnet.phantom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import java.io.File
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.XrayConfigBuilder

class PhantomVpnService : VpnService() {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var prefs: SharedPreferences
    private var vpnInterface: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false
    @Volatile private var state = STATUS_DISCONNECTED

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Phantom VPN"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> ioScope.launch {
                mutex.withLock { stopLocked() }
                stopSelf()
            }
            ACTION_QUERY -> sendStatus(state)
            ACTION_KEEP_ALIVE -> ioScope.launch {
                mutex.withLock { configureWakeLock() }
            }
            ACTION_START -> {
                val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                ioScope.launch { mutex.withLock { startLocked(profile) } }
            }
            else -> {
                if (prefs.getBoolean("vpn_running", false)) {
                    val profile = prefs.getString("vpn_last_profile", "").orEmpty()
                    ioScope.launch { mutex.withLock { startLocked(profile) } }
                } else {
                    stopSelf()
                }
            }
        }
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private fun startLocked(raw: String) {
        stopLocked(false)
        setState(STATUS_STARTING)
        try {
            require(raw.isNotBlank()) { "Профиль сервера пуст" }
            val scheme = if (raw.trimStart().startsWith("{")) "xray"
                else raw.substringBefore(':').lowercase()
            val config = XrayConfigBuilder.build(ServerProfile("runtime", "runtime", scheme, raw = raw))
            // The VPN's own sockets must not recursively enter the VPN TUN.
            // The app is intentionally excluded; user apps still route via VPN.
            val tun = Builder()
                .setSession("Phantom")
                .setMtu(1400)
                .addAddress("10.111.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)
                .establish() ?: error("Android не создал VPN-интерфейс")
            vpnInterface = tun

            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(stageCoreAssets(), "phantom-$packageName")
            val active = Libv2ray.newCoreController(callback)
            controller = active
            active.startLoop(config, tun.fd)

            // Core startup alone is not proof of Internet access.
            // Measure via the configured Xray outbound, not device networking.
            val verified = listOf(
                "https://www.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
            ).any { endpoint -> runCatching { active.measureDelay(endpoint) > 0L }.getOrDefault(false) }
            require(verified) { "Туннель запущен, но интернет через сервер не отвечает. Выберите другой сервер." }

            running = true
            prefs.edit().putString("vpn_last_profile", raw).putBoolean("vpn_running", true).apply()
            configureWakeLock()
            setState(STATUS_CONNECTED)
            updateNotification("VPN подключён")
        } catch (e: Exception) {
            stopLocked(false)
            setState(STATUS_ERROR, e.message ?: "Не удалось подключиться")
            updateNotification("Ошибка подключения")
        }
    }

    private fun stageCoreAssets(): String {
        val directory = File(filesDir, "xray-assets").apply { mkdirs() }
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val destination = File(directory, name)
            if (!destination.exists() || destination.length() < 1024) {
                val temporary = File(directory, "$name.tmp")
                assets.open(name).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                require(temporary.renameTo(destination)) { "Не удалось подготовить базу $name" }
            }
        }
        return directory.absolutePath
    }

    private fun stopLocked(report: Boolean = true) {
        running = false
        releaseWakeLock()
        runCatching { controller?.stopLoop() }
        controller = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        prefs.edit().putBoolean("vpn_running", false).apply()
        if (report) {
            setState(STATUS_DISCONNECTED)
            updateNotification("VPN отключён")
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
        state = next
        sendStatus(next, error)
    }

    private fun sendStatus(status: String, error: String = "") {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, status).putExtra(EXTRA_ERROR, error)
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phantom VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID).setContentTitle("Phantom")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onRevoke() {
        ioScope.launch { mutex.withLock { stopLocked() }; stopSelf() }
        super.onRevoke()
    }

    override fun onDestroy() {
        releaseWakeLock()
        runCatching { controller?.stopLoop() }
        runCatching { vpnInterface?.close() }
        ioScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_START = "xyz.wraithnet.phantom.START_VPN"
        const val ACTION_STOP = "xyz.wraithnet.phantom.STOP_VPN"
        const val ACTION_QUERY = "xyz.wraithnet.phantom.QUERY_VPN"
        const val ACTION_KEEP_ALIVE = "xyz.wraithnet.phantom.KEEP_ALIVE"
        const val ACTION_STATUS = "xyz.wraithnet.phantom.VPN_STATUS"
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
