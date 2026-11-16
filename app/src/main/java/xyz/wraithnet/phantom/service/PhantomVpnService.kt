package xyz.wraithnet.phantom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.XrayConfigBuilder

class PhantomVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var controller: CoreController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Запуск Phantom"))
        Seq.setContext(applicationContext)
        Libv2ray.initCoreEnv("", "phantom-${applicationContext.packageName}")
        controller = Libv2ray.newCoreController(callback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            runCatching { startVpn(intent.getStringExtra(EXTRA_PROFILE).orEmpty()) }
                .onFailure { fail(it.message ?: "Не удалось запустить Xray") }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(raw: String) {
        require(raw.isNotBlank()) { "Профиль сервера пуст" }
        sendStatus(STATUS_STARTING)
        stopVpn()
        val profile = ServerProfile("runtime", "runtime", raw.substringBefore(':').lowercase(), raw = raw)
        val config = XrayConfigBuilder.build(profile)
        vpnInterface = Builder()
            .setSession("Phantom")
            .setMtu(1500)
            .addAddress("10.0.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .establish()
        requireNotNull(vpnInterface) { "Android не создал VPN-интерфейс" }
        controller?.startLoop(config, vpnInterface!!.fd)
        sendStatus(STATUS_CONNECTED)
        updateNotification("Phantom подключён")
    }

    private fun stopVpn() {
        runCatching { controller?.stopLoop() }
        controller = Libv2ray.newCoreController(callback)
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        sendStatus(STATUS_DISCONNECTED)
        updateNotification("Phantom отключён")
    }

    private fun fail(message: String) {
        stopVpn()
        sendStatus(STATUS_ERROR, message)
    }

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String): Long {
            if (code != 0L) sendStatus(STATUS_ERROR, message)
            return 0
        }
    }

    private fun sendStatus(status: String, error: String? = null) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, status).putExtra(EXTRA_ERROR, error.orEmpty()))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phantom VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String): Notification = if (Build.VERSION.SDK_INT >= 26) {
        Notification.Builder(this, CHANNEL_ID).setContentTitle("Phantom").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning).setOngoing(true).build()
    } else Notification.Builder(this).setContentTitle("Phantom").setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_warning).setOngoing(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_START = "xyz.wraithnet.phantom.START_VPN"
        const val ACTION_STOP = "xyz.wraithnet.phantom.STOP_VPN"
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
