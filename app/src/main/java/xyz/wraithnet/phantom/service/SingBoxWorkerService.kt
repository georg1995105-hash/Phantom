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
import java.util.concurrent.RejectedExecutionException

/**
 * Only this non-exported bound service loads libbox; the OS places it in :singbox.
 * The main VPN process loads Xray only. This service never establishes a TUN.
 * Extending VpnService enables socket protect(fd) for native outbound dials.
 */
class SingBoxWorkerService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    @Volatile private var activeSessionId: String? = null
    private val backend by lazy { SingBoxNativeBackend(this) }

    /** Never let a late Binder message crash the OS process after onDestroy. */
    private fun submit(action: () -> Unit): Boolean {
        if (destroyed || executor.isShutdown) return false
        return try {
            executor.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun replyClosed(recipient: Messenger?) {
        val reply = Message.obtain(null, MSG_ERROR)
        reply.data = android.os.Bundle().apply {
            putString(KEY_ERROR, "Процесс sing-box завершает работу; повторите подключение")
        }
        runCatching { recipient?.send(reply) }
    }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_START -> {
                    val raw = message.data.getString(KEY_PROFILE).orEmpty()
                    val sessionId = message.data.getString(KEY_SESSION).orEmpty()
                    val recipient = message.replyTo
                    if (!submit {
                        try {
                            check(!destroyed) { "Процесс закрыт" }
                            check(sessionId.isNotBlank()) { "Нет идентификатора сеанса sing-box" }
                            // The old asynchronous STOP may arrive after a new START.
                            // Transition to the new session and reject all stale STOPs.
                            if (activeSessionId != null && activeSessionId != sessionId) {
                                backend.stop()
                                activeSessionId = null
                            }
                            val port = backend.start(raw)
                            activeSessionId = sessionId
                            if (destroyed) {
                                if (activeSessionId == sessionId) {
                                    backend.stop()
                                    activeSessionId = null
                                }
                                return@submit
                            }
                            val reply = Message.obtain(null, MSG_STARTED, port, Process.myPid())
                            runCatching { recipient?.send(reply) }
                        } catch (e: Exception) {
                            if (activeSessionId == sessionId) {
                                runCatching { backend.stop() }
                                activeSessionId = null
                            }
                            val reply = Message.obtain(null, MSG_ERROR)
                            // Never echo a native error or the supplied URL: it can contain credentials.
                            val reason = e.message.orEmpty()
                            val safeMessage = when {
                                reason.contains("DNS физической сети") ->
                                    "Не удалось определить IP сервера TUIC/AnyTLS через физическую сеть"
                                reason.contains("Нет доступной физической сети") ||
                                    reason.contains("не обнаружил физическую сеть") ->
                                    "Android не предоставляет физическую сеть для TUIC/AnyTLS"
                                reason.contains("не запустил наблюдение") ||
                                    reason.contains("libbox:") ->
                                    "Ошибка интеграции sing-box с сетевыми интерфейсами Android"
                                else -> "Ошибка запуска sing-box (${e.javaClass.simpleName})"
                            }
                            reply.data = android.os.Bundle().apply {
                                putString(KEY_ERROR, safeMessage)
                            }
                            runCatching { recipient?.send(reply) }
                        }
                    }) replyClosed(recipient)
                }
                MSG_STOP -> {
                    val sessionId = message.data.getString(KEY_SESSION)
                    submit {
                        if (sessionId != null && sessionId == activeSessionId) {
                            runCatching { backend.stop() }
                            activeSessionId = null
                        }
                    }
                }
                MSG_PING -> {
                    val reply = Message.obtain(null, MSG_PONG,
                        if (!destroyed && activeSessionId != null && backend.isRunning) 1 else 0, Process.myPid())
                    runCatching { message.replyTo?.send(reply) }
                }
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        destroyed = true
        runCatching {
            executor.execute {
                runCatching { backend.stop() }
                activeSessionId = null
            }
        }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_STARTED = 3
        const val MSG_ERROR = 4
        const val MSG_PING = 5
        const val MSG_PONG = 6
        const val KEY_PROFILE = "profile"
        const val KEY_SESSION = "session"
        const val KEY_ERROR = "error"
    }
}
