package xyz.wraithnet.phantom.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
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
    private val sessionCreatedAt = System.currentTimeMillis()
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
            retireStaleWorker()
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
            runCatching { stop() }
            throw e
        }
    }

    /**
     * Android can kill a child Go process without delivering a Java exception.
     * Ask the OS for the reason of THIS session's worker death rather than
     * reporting a missing SOCKS listener as a remote TUIC server failure.
     * Only coarse OS reason codes are exposed; crash traces are never copied.
     */
    private fun recentAndroidExit(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return runCatching {
            val activity = context.getSystemService(ActivityManager::class.java)
            val exit = activity.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .firstOrNull {
                    it.processName == "${context.packageName}:singbox" &&
                        it.timestamp >= sessionCreatedAt - 1_000L
                } ?: return@runCatching ""
            val reason = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH -> "сбой Android/Java"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой нативного движка"
                ApplicationExitInfo.REASON_ANR -> "процесс не отвечал"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "нехватка памяти"
                ApplicationExitInfo.REASON_SIGNALED -> "системный сигнал ${exit.status}"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "остановлен системой или пользователем"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "ограничение ресурсов"
                else -> "код причины Android: ${exit.reason}"
            }
            "; Android: $reason"
        }.getOrDefault("")
    }

    /** Read-only remote health status. Never includes server addresses or credentials. */
    fun workerStatus(): String {
        val peer = remote ?: return "дочерний процесс не подключён" + recentAndroidExit()
        val answer = CountDownLatch(1)
        val state = AtomicInteger(-1)
        val answeredPid = AtomicInteger(-1)
        val lastReason = AtomicReference("")
        val recipient = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what == SingBoxWorkerService.MSG_PONG) {
                    state.set(message.arg1)
                    answeredPid.set(message.arg2)
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
            else {
                val replaced = workerPid > 0 && answeredPid.get() != workerPid
                when {
                    replaced -> "Android создал новый процесс sing-box вместо запущенного" +
                        recentAndroidExit()
                    state.get() == 1 -> "движок запущен, локальный SOCKS5 недоступен"
                    state.get() == 0 -> "движок sing-box остановлен: " +
                        (lastReason.get().ifBlank { "причина не сообщена" }) +
                        recentAndroidExit()
                    else -> "дочерний процесс вернул неизвестное состояние"
                }
            }
        } catch (e: Exception) {
            "связь с дочерним процессом потеряна" + recentAndroidExit()
        }
    }

    private fun workerAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            // Signal zero checks whether THIS PID still exists; it never kills.
            Os.kill(pid, 0)
            true
        } catch (e: ErrnoException) {
            e.errno != OsConstants.ESRCH
        }
    }

    /** Wait for an isolated native runtime to actually exit before restarting. */
    private fun waitForExit(pid: Int, timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (workerAlive(pid) && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(40)
        }
        return !workerAlive(pid)
    }

    /**
     * A fresh VPN service instance can be created before its predecessor's
     * onDestroy cleanup thread has finished. Never inherit a live Go runtime.
     * Only the app's exact :singbox process is eligible for retirement.
     */
    private fun retireStaleWorker() {
        val ownProcess = "${context.packageName}:singbox"
        val stale = context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses.orEmpty()
            .filter { it.processName == ownProcess && it.pid != Process.myPid() }
        for (process in stale) {
            Process.killProcess(process.pid)
            check(waitForExit(process.pid, 1800)) {
                "Предыдущий процесс sing-box ещё работает"
            }
        }
    }

    fun stop() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Остановка sing-box должна выполняться в фоновом потоке"
        }
        val oldConnection: ServiceConnection?
        val oldRemote: Messenger?
        val hadStartedLease: Boolean
        val wasBound: Boolean
        val previousPid: Int
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
            previousPid = workerPid
            workerPid = -1
        }

        // START and STOP are serialized by the main VPN mutex.
        // A write-only STOP is insufficient: it can race with the next START.
        val answered = CountDownLatch(1)
        var sent = false
        if (hadStartedLease && oldRemote != null) {
            val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if (message.what == SingBoxWorkerService.MSG_STOPPED) answered.countDown()
                }
            })
            sent = runCatching {
                oldRemote.send(Message.obtain(null, SingBoxWorkerService.MSG_STOP).apply {
                    data = android.os.Bundle().apply {
                        putString(SingBoxWorkerService.KEY_SESSION, sessionId)
                    }
                    replyTo = receiver
                })
                true
            }.getOrDefault(false)
        }
        if (sent) answered.await(1500, TimeUnit.MILLISECONDS)
        if (wasBound && oldConnection != null) {
            runCatching { context.unbindService(oldConnection) }
        }
        // If the worker crashed during STOP or Binder was lost, release the
        // started lease so Android cannot keep an orphan service alive.
        if (hadStartedLease && (!sent || previousPid <= 0) &&
            (previousPid <= 0 || workerAlive(previousPid))
        ) {
            // Never stop a *newer* worker service on behalf of a stale session.
            runCatching { context.stopService(Intent(context, SingBoxWorkerService::class.java)) }
        }
        if (previousPid > 0 && !waitForExit(previousPid, 1800)) {
            // Only kill our own known :singbox process, never a recycled PID.
            val ownWorker = runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .runningAppProcesses?.any {
                        it.pid == previousPid &&
                            it.processName == "${context.packageName}:singbox"
                    } == true
            }.getOrDefault(false)
            if (ownWorker) {
                Process.killProcess(previousPid)
            }
            check(waitForExit(previousPid, 1200)) {
                "Не удалось завершить старый процесс sing-box"
            }
        }
    }
}
