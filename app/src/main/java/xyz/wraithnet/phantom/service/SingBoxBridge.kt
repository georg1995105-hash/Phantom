package xyz.wraithnet.phantom.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * Main-process IPC client. Never import or initialize libbox in this process:
 * Xray's independent Go runtime owns the VPN TUN here.
 * The bound worker runs in Android :singbox and owns its own Go runtime.
 */
internal class SingBoxBridge(
    private val context: Context,
    private val onUnexpectedExit: () -> Unit = {},
) {
    private val sessionId = UUID.randomUUID().toString()
    @Volatile private var remote: Messenger? = null
    @Volatile private var stopping = false
    @Volatile var workerPid: Int = -1
        private set
    private var connection: ServiceConnection? = null
    private var bound = false
    private var startedLease = false

    fun start(raw: String): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Нельзя запускать sing-box с главного потока Android"
        }
        synchronized(this) {
            check(connection == null) { "sing-box уже запущен" }
            stopping = false
        }
        val connected = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val port = AtomicInteger(-1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!stopping) remote = Messenger(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                fail("Процесс sing-box завершился")
            }
            override fun onBindingDied(name: ComponentName) {
                fail("Связь с процессом sing-box потеряна")
            }
            override fun onNullBinding(name: ComponentName) {
                fail("Процесс sing-box не предоставил интерфейс")
            }
            private fun fail(reason: String) {
                if (stopping) return
                failure.compareAndSet(null, reason)
                remote = null
                connected.countDown()
                answered.countDown()
                onUnexpectedExit()
            }
        }
        synchronized(this) { connection = serviceConnection }
        try {
            // This must run while the main PhantomVpnService is foreground.
            // A STARTED lease keeps the separate Go process alive across
            // Android bind/unbind, network changes and TUN establishment.
            val lease = Intent(context, SingBoxWorkerService::class.java).apply {
                action = SingBoxWorkerService.ACTION_LEASE
                putExtra(SingBoxWorkerService.KEY_SESSION, sessionId)
            }
            check(context.startService(lease) != null) {
                "Android не разрешил закрепить процесс sing-box"
            }
            startedLease = true
            val didBind = context.bindService(
                Intent(context, SingBoxWorkerService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            check(didBind) { "Android не смог запустить процесс sing-box" }
            synchronized(this) { bound = true }
            check(connected.await(12, TimeUnit.SECONDS)) { "Таймаут запуска процесса sing-box" }
            failure.get()?.let { error(it) }
            val binder = remote ?: error("Процесс sing-box не подключён")
            val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    when (message.what) {
                        SingBoxWorkerService.MSG_STARTED -> {
                            port.set(message.arg1)
                            workerPid = message.arg2
                        }
                        SingBoxWorkerService.MSG_ERROR -> {
                            failure.compareAndSet(null,
                                message.data.getString(SingBoxWorkerService.KEY_ERROR)
                                    ?: "Не удалось запустить sing-box")
                        }
                        else -> failure.compareAndSet(null, "Неизвестный ответ sing-box")
                    }
                    answered.countDown()
                }
            })
            val command = Message.obtain(null, SingBoxWorkerService.MSG_START)
            command.data = android.os.Bundle().apply {
                putString(SingBoxWorkerService.KEY_PROFILE, raw)
                putString(SingBoxWorkerService.KEY_SESSION, sessionId)
            }
            command.replyTo = receiver
            binder.send(command)
            check(answered.await(35, TimeUnit.SECONDS)) { "Таймаут запуска ядра sing-box" }
            failure.get()?.let { error(it) }
            check(workerPid > 0 && workerPid != Process.myPid()) {
                "sing-box не изолирован от процесса Xray"
            }
            val actualPort = port.get()
            check(actualPort in 1024..65535) { "Движок sing-box не вернул SOCKS-порт" }
            // Only a real SOCKS5 negotiation proves that the inbound is usable.
            // A TCP-only probe can succeed before the handler has stopped/crashed.
            var probe = LocalSocks5Probe.Result(false, "не проверен")
            var successfulHandshakes = 0
            repeat(6) {
                if (successfulHandshakes < 3) {
                    probe = LocalSocks5Probe.check(actualPort)
                    successfulHandshakes = if (probe.ready) successfulHandshakes + 1 else 0
                    if (successfulHandshakes < 3) Thread.sleep(150)
                }
            }
            check(successfulHandshakes >= 3) {
                "Локальный SOCKS5 sing-box нестабилен: ${probe.reason}"
            }
            return actualPort
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /** Read-only remote health status. Never includes server addresses or credentials. */
    fun workerStatus(): String {
        val peer = remote ?: return "дочерний процесс не подключён"
        val answer = CountDownLatch(1)
        val state = AtomicInteger(-1)
        val lastReason = AtomicReference("")
        val recipient = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what == SingBoxWorkerService.MSG_PONG) {
                    state.set(message.arg1)
                    lastReason.set(message.data.getString(SingBoxWorkerService.KEY_STOP_REASON).orEmpty())
                }
                answer.countDown()
            }
        })
        return try {
            peer.send(Message.obtain(null, SingBoxWorkerService.MSG_PING).apply {
                replyTo = recipient
            })
            if (!answer.await(2, TimeUnit.SECONDS)) "дочерний процесс не отвечает"
            else when (state.get()) {
                1 -> "движок запущен, локальный SOCKS5 недоступен"
                0 -> "движок sing-box остановлен: " +
                    (lastReason.get().ifBlank { "причина не сообщена" })
                else -> "дочерний процесс вернул неизвестное состояние"
            }
        } catch (e: Exception) {
            "связь с дочерним процессом потеряна"
        }
    }

    fun stop() {
        val oldConnection: ServiceConnection?
        val oldRemote: Messenger?
        val hadStartedLease: Boolean
        val wasBound: Boolean
        synchronized(this) {
            stopping = true
            oldConnection = connection
            connection = null
            oldRemote = remote
            remote = null
            hadStartedLease = startedLease
            startedLease = false
            wasBound = bound
            bound = false
            workerPid = -1
        }
        // STOP names its session; a late command must never terminate a
        // replacement connection. The started lease prevents onDestroy before
        // STOP is processed, and onUnbind handles missing STOP defensively.
        var stopSent = false
        if (hadStartedLease && oldRemote != null) {
            stopSent = runCatching {
                oldRemote.send(Message.obtain(null, SingBoxWorkerService.MSG_STOP).apply {
                    data = android.os.Bundle().apply {
                        putString(SingBoxWorkerService.KEY_SESSION, sessionId)
                    }
                })
                true
            }.getOrDefault(false)
        }
        if (wasBound && oldConnection != null) {
            runCatching { context.unbindService(oldConnection) }
        }
        if (hadStartedLease && !stopSent) {
            // Only if IPC was unavailable; normally STOP self-releases the lease.
            // Main VPN serializes starts/stops under a mutex.
            runCatching {
                context.stopService(Intent(context, SingBoxWorkerService::class.java))
            }
        }
    }
}
