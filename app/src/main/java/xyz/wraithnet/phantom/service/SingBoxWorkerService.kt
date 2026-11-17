package xyz.wraithnet.phantom.service

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * A started AND bound Android service in the dedicated :singbox process.
 *
 * The separate Go runtime must stay alive while the main process establishes
 * Android TUN and starts Xray. A bound-only service has no independent lease,
 * and can be torn down on a transient unbind. Explicit startService leases
 * survive rebinds and are released only by their own session IDs.
 *
 * This service uses VpnService.protect(fd) for QUIC, but it NEVER establishes
 * another Android TUN. PhantomVpnService remains the one registered VPN.
 */
class SingBoxWorkerService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val leaseStartIds = ConcurrentHashMap<String, Int>()
    @Volatile private var destroyed = false
    @Volatile private var activeSessionId: String? = null
    @Volatile private var lastStopReason = "не запускался"
    private val backend by lazy { SingBoxNativeBackend(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(KEY_SESSION)
        if (intent?.action != ACTION_LEASE || id.isNullOrBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        leaseStartIds[id] = startId
        return START_NOT_STICKY
    }

    /** No binder message is allowed to crash the worker after onDestroy. */
    private fun submit(action: () -> Unit): Boolean {
        if (destroyed || executor.isShutdown) return false
        return try {
            executor.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun fail(recipient: Messenger?, reason: String) {
        val reply = Message.obtain(null, MSG_ERROR)
        reply.data = Bundle().apply { putString(KEY_ERROR, reason) }
        runCatching { recipient?.send(reply) }
    }

    private fun releaseLease(session: String) {
        leaseStartIds.remove(session)?.let { startId -> stopSelfResult(startId) }
    }

    private fun stopSession(session: String, reason: String) {
        if (activeSessionId == session) {
            runCatching { backend.stop() }
            activeSessionId = null
            lastStopReason = reason
        }
        releaseLease(session)
    }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_START -> {
                    val raw = message.data.getString(KEY_PROFILE).orEmpty()
                    val id = message.data.getString(KEY_SESSION).orEmpty()
                    val recipient = message.replyTo
                    if (!submit {
                        try {
                            check(!destroyed) { "Процесс закрыт" }
                            check(id.isNotBlank() && leaseStartIds.containsKey(id)) {
                                "Сеанс sing-box не имеет активной Android-службы"
                            }
                            activeSessionId?.takeIf { it != id }?.let { old ->
                                stopSession(old, "смена профиля")
                            }
                            check(activeSessionId != id) { "Сеанс sing-box уже запущен" }
                            val port = backend.start(raw)
                            activeSessionId = id
                            lastStopReason = "запущен"
                            if (destroyed) {
                                stopSession(id, "закрытие Android-службы")
                                fail(recipient, "Android закрывает процесс sing-box")
                                return@submit
                            }
                            runCatching {
                                recipient?.send(Message.obtain(null, MSG_STARTED, port, Process.myPid()))
                            }
                        } catch (e: Exception) {
                            if (activeSessionId == id) stopSession(id, "ошибка запуска")
                            else releaseLease(id)
                            // Never put a raw URL or native Go stack in IPC/errors.
                            val reason = e.message.orEmpty()
                            val safe = when {
                                reason.contains("DNS физической сети") ->
                                    "DNS физической сети не определил сервер TUIC/AnyTLS"
                                reason.contains("Нет доступной физической сети") ||
                                    reason.contains("не обнаружил физическую сеть") ->
                                    "Android не предоставляет физическую сеть"
                                reason.contains("libbox:") || reason.contains("не запустил наблюдение") ->
                                    "Ошибка сетевой интеграции sing-box"
                                else -> "Ошибка запуска sing-box (${e.javaClass.simpleName})"
                            }
                            lastStopReason = "ошибка запуска"
                            fail(recipient, safe)
                        }
                    }) fail(recipient, "Сервис sing-box закрыт Android")
                }
                MSG_STOP -> {
                    val id = message.data.getString(KEY_SESSION)
                    if (!id.isNullOrBlank()) submit { stopSession(id, "остановлен пользователем") }
                }
                MSG_PING -> {
                    val running = !destroyed && activeSessionId != null && backend.isRunning
                    val reply = Message.obtain(null, MSG_PONG,
                        if (running) 1 else 0, Process.myPid())
                    reply.data = Bundle().apply {
                        putString(KEY_STOP_REASON, lastStopReason)
                    }
                    runCatching { message.replyTo?.send(reply) }
                }
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Defensive cleanup if the controlling process vanished before MSG_STOP.
        // A replacement session is never stopped because IDs are checked in the
        // serial executor and stopSelfResult cannot stop a newer startId.
        val orphan = activeSessionId
        if (orphan != null) submit {
            if (activeSessionId == orphan) stopSession(orphan, "потеряна привязка Android")
        }
        return true
    }

    override fun onDestroy() {
        destroyed = true
        runCatching {
            executor.execute {
                runCatching { backend.stop() }
                activeSessionId = null
                leaseStartIds.clear()
                lastStopReason = "Android уничтожил сервис"
            }
        }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LEASE = "xyz.wraithnet.phantom.SINGBOX_LEASE"
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_STARTED = 3
        const val MSG_ERROR = 4
        const val MSG_PING = 5
        const val MSG_PONG = 6
        const val KEY_PROFILE = "profile"
        const val KEY_SESSION = "session"
        const val KEY_ERROR = "error"
        const val KEY_STOP_REASON = "stop_reason"
    }
}
