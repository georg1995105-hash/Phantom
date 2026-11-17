package xyz.wraithnet.phantom.service

import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.Executors

/**
 * Only this non-exported bound service loads libbox; the OS places it in :singbox.
 * The main VPN process loads Xray only. This service never establishes a TUN.
 * Extending VpnService enables socket protect(fd) for native outbound dials.
 */
class SingBoxWorkerService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    private val backend by lazy { SingBoxNativeBackend(this) }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_START -> {
                    val raw = message.data.getString(KEY_PROFILE).orEmpty()
                    val recipient = message.replyTo
                    executor.execute {
                        try {
                            check(!destroyed) { "Процесс закрыт" }
                            val port = backend.start(raw)
                            if (destroyed) {
                                backend.stop()
                                return@execute
                            }
                            val reply = Message.obtain(null, MSG_STARTED, port, Process.myPid())
                            runCatching { recipient?.send(reply) }
                        } catch (e: Exception) {
                            runCatching { backend.stop() }
                            val reply = Message.obtain(null, MSG_ERROR)
                            // Never echo a native error or the supplied URL: it can contain credentials.
                            reply.data = android.os.Bundle().apply {
                                putString(KEY_ERROR, "Ошибка запуска sing-box (${e.javaClass.simpleName})")
                            }
                            runCatching { recipient?.send(reply) }
                        }
                    }
                }
                MSG_STOP -> executor.execute { runCatching { backend.stop() } }
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        destroyed = true
        executor.execute { runCatching { backend.stop() } }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_STARTED = 3
        const val MSG_ERROR = 4
        const val KEY_PROFILE = "profile"
        const val KEY_ERROR = "error"
    }
}
