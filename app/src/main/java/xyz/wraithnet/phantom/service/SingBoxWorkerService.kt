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
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var boundClient = false
    // Losing the last Binder connection is not the same as an explicit STOP.
    // Android may detach/rebind clients during a VPN/network transition.
    // Keep the started-service lease during a short reconnect window, then
    // release an orphan if the controlling process never comes back.
    private val orphanCleanup = Runnable {
        if (!destroyed && !boundClient) {
            val orphan = activeSessionId
            if (orphan != null) submit {
                if (!boundClient && activeSessionId == orphan) {
                    retireProcess("управляющий процесс не переподключился")
                }
            }
        }
    }
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

    /**
     * A native Go runtime is NEVER restarted within the same Linux process.
     * Closing and immediately reopening libbox during quick profile switches
     * can race native callbacks. Kill only the dedicated :singbox process;
     * Android closes every socket and FD, while the main Xray VPN stays alive.
     * No reusable Go state crosses a session boundary.
     */
    private fun retireProcess(reason: String) {
        lastStopReason = reason
        activeSessionId = null
        leaseStartIds.clear()
        Process.killProcess(Process.myPid())
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
                            check(activeSessionId == null) {
                                "Предыдущий сеанс sing-box ещё завершается"
                            }
                            val port = backend.start(raw)
                            activeSessionId = id
                            lastStopReason = "запущен"
                            if (destroyed) {
                                fail(recipient, "Android закрывает процесс sing-box")
                                retireProcess("закрытие Android-службы")
                                return@submit
                            }
                            runCatching {
                                recipient?.send(Message.obtain(null, MSG_STARTED, port, Process.myPid()))
                            }
                        } catch (e: Exception) {
                            // Never restart a possibly half-initialized Go runtime.
                            // Never put a raw URL or native Go stack in IPC/errors.
                            val reason = e.message.orEmpty()
                            val safe = when {
                                reason.contains("DNS физической сети") ->
                                    "DNS физической сети не определил сервер TUIC/AnyTLS"
                                reason.contains("Нет доступной физической сети") ||
                                    reason.contains("не обнаружил физическую сеть") ->
                                    "Android не предоставляет физическую сеть"
                                reason.contains("обход VPN") || reason.contains("protect", true) ->
                                    "Android не разрешил физический сокет sing-box вне VPN"
                                reason.contains("libbox:") || reason.contains("не запустил наблюдение") ->
                                    "Ошибка сетевой интеграции sing-box"
                                else -> "Ошибка запуска sing-box (${e.javaClass.simpleName})"
                            }
                            lastStopReason = "ошибка запуска"
                            // Binder replies are asynchronous. Killing the Go
                            // process immediately can deliver onServiceDisconnected
                            // before MSG_ERROR and hide the real failure reason.
                            // Give the main process a short chance to receive the
                            // classified, credential-free error, then retire the
                            // non-reusable native runtime unconditionally.
                            fail(recipient, safe)
                            retireAfterStartupError()
                        } catch (_: LinkageError) {
                            lastStopReason = "ошибка загрузки JNI"
                            fail(recipient, "Android не загрузил нативную библиотеку sing-box")
                            retireAfterStartupError()
                        }
                    }) fail(recipient, "Сервис sing-box закрыт Android")
                }
                MSG_STOP -> {
                    val id = message.data.getString(KEY_SESSION)
                    val recipient = message.replyTo
                    if (!id.isNullOrBlank()) {
                        if (!submit {
                            // A late STOP for an older session must not kill a
                            // newer one; only the owner may retire this worker.
                            if (id == activeSessionId || leaseStartIds.containsKey(id)) {
                                runCatching { recipient?.send(Message.obtain(null, MSG_STOPPED)) }
                                retireProcess("сеанс завершён")
                            } else {
                                runCatching { recipient?.send(Message.obtain(null, MSG_STOPPED)) }
                            }
                        }) fail(recipient, "Процесс sing-box уже закрывается")
                    }
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

    private fun retireAfterStartupError() {
        if (!mainHandler.postDelayed({
                if (!destroyed) retireProcess(lastStopReason)
            }, 500L)) {
            retireProcess(lastStopReason)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClient = true
        mainHandler.removeCallbacks(orphanCleanup)
        return messenger.binder
    }

    override fun onRebind(intent: Intent?) {
        boundClient = true
        mainHandler.removeCallbacks(orphanCleanup)
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Do NOT immediately stop sing-box here. The started lease survives
        // transient unbinds; only an explicit session STOP may stop it now.
        boundClient = false
        mainHandler.removeCallbacks(orphanCleanup)
        mainHandler.postDelayed(orphanCleanup, 15_000L)
        return true
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(orphanCleanup)
        // The worker may have been destroyed while JNI still owns live Go
        // callbacks. Never call native closeService() on Android's main thread.
        // End this dedicated process instead of leaving a half-closed runtime
        // to be reused by a subsequent TUIC connection.
        executor.shutdownNow()
        super.onDestroy()
        // A destroyed worker process must never host another Go runtime,
        // even if native startup failed before activeSessionId was published.
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val ACTION_LEASE = "xyz.wraithnet.phantom.SINGBOX_LEASE"
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_STARTED = 3
        const val MSG_ERROR = 4
        const val MSG_PING = 5
        const val MSG_PONG = 6
        const val MSG_STOPPED = 7
        const val KEY_PROFILE = "profile"
        const val KEY_SESSION = "session"
        const val KEY_ERROR = "error"
        const val KEY_STOP_REASON = "stop_reason"
    }
}
