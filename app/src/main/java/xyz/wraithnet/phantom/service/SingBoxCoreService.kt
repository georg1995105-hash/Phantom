package xyz.wraithnet.phantom.service

import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

/**
 * Separate Android process for libbox's independent Go runtime.
 *
 * Extending VpnService enables protect(fd) for sing-box sockets but this service
 * NEVER establishes a VPN, and it has no VPN intent filter in the manifest.
 * The main-process PhantomVpnService exclusively owns the Android TUN.
 */
class SingBoxCoreService : VpnService() {
    private val worker = HandlerThread("phantom-singbox-worker")
    private lateinit var messenger: Messenger
    private var backend: SingBoxNativeBackend? = null

    override fun onCreate() {
        super.onCreate()
        worker.start()
        messenger = Messenger(object : Handler(worker.looper) {
            override fun handleMessage(message: Message) {
                when (message.what) {
                    SingBoxBridge.MSG_START -> {
                        val raw = message.data.getString(SingBoxBridge.KEY_PROFILE).orEmpty()
                        val response = try {
                            require(raw.isNotBlank()) { "Пустая конфигурация TUIC/AnyTLS" }
                            backend?.stop()
                            val engine = SingBoxNativeBackend(this@SingBoxCoreService)
                            backend = engine
                            Message.obtain(null, SingBoxBridge.MSG_READY,
                                engine.start(raw), android.os.Process.myPid())
                        } catch (e: Exception) {
                            Log.e("PhantomSingBox", "Native backend startup failed: " +
                                e.javaClass.simpleName)
                            Message.obtain(null, SingBoxBridge.MSG_ERROR).apply {
                                data = android.os.Bundle().apply {
                                    putString(SingBoxBridge.KEY_ERROR,
                                        "sing-box: " + e.javaClass.simpleName +
                                            ". Проверьте параметры подключения")
                                }
                            }
                        }
                        try {
                            message.replyTo?.send(response)
                        } catch (_: RemoteException) {
                            backend?.stop()
                            backend = null
                        }
                    }
                    SingBoxBridge.MSG_STOP -> {
                        backend?.stop()
                        backend = null
                    }
                    else -> super.handleMessage(message)
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The main VPN session owns the binding: once it stops, release the
        // secondary process and its sockets without affecting primary Xray.
        stopSelf()
        return false
    }

    override fun onDestroy() {
        Handler(worker.looper).post {
            backend?.stop()
            backend = null
            worker.quitSafely()
        }
        super.onDestroy()
    }
}
