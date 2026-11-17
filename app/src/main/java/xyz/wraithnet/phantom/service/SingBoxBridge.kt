package xyz.wraithnet.phantom.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binder-only bridge to the isolated :singbox Android process.
 * No libbox, sb.Seq or Go types may be referenced in the Xray process.
 * Both processes share loopback so Xray TUN can forward TCP/UDP through
 * the secondary sing-box SOCKS5 listener without creating another VPN.
 */
internal class SingBoxBridge(
    private val context: Context,
    private val onDied: () -> Unit = {},
) {
    constructor(vpn: VpnService) : this(vpn.applicationContext)

    internal companion object {
        const val MSG_START = 1
        const val MSG_READY = 2
        const val MSG_ERROR = 3
        const val MSG_STOP = 4
        const val KEY_PROFILE = "profile"
        const val KEY_ERROR = "error"
    }

    @Volatile private var remote: Messenger? = null
    @Volatile private var closed = false
    @Volatile private var active = false
    @Volatile private var bound = false
    @Volatile private var serverPort = 0
    @Volatile private var startupError = ""
    @Volatile internal var remotePid = -1
        private set
    private var connection: ServiceConnection? = null
    private var pending: CountDownLatch? = null

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (closed) return
            when (message.what) {
                MSG_READY -> {
                    serverPort = message.arg1
                    remotePid = message.arg2
                    pending?.countDown()
                }
                MSG_ERROR -> {
                    startupError = message.data.getString(KEY_ERROR)
                        ?: "Ошибка запуска sing-box"
                    pending?.countDown()
                }
                else -> super.handleMessage(message)
            }
        }
    })

    fun start(raw: String): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Нельзя запускать ядро VPN в главном потоке Android"
        }
        check(!bound && !closed) { "Движок уже запущен или закрыт" }
        require(raw.isNotBlank()) { "Отсутствует конфигурация" }
        val ready = CountDownLatch(1)
        pending = ready
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed) return
                try {
                    val ipc = Messenger(service)
                    remote = ipc
                    val request = Message.obtain(null, MSG_START).apply {
                        replyTo = replies
                        data = Bundle().apply { putString(KEY_PROFILE, raw) }
                    }
                    ipc.send(request)
                } catch (e: Exception) {
                    startupError = "Не удалось связаться с движком sing-box"
                    ready.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (closed) return
                remote = null
                startupError = "Процесс sing-box неожиданно остановился"
                ready.countDown()
                if (active) onDied()
            }

            override fun onBindingDied(name: ComponentName) {
                onServiceDisconnected(name)
            }
        }
        connection = callback
        try {
            bound = context.applicationContext.bindService(
                Intent(context, SingBoxCoreService::class.java),
                callback, Context.BIND_AUTO_CREATE)
            check(bound) { "Android не запустил процесс sing-box" }
            check(ready.await(20, TimeUnit.SECONDS)) {
                "Истекло время запуска sing-box"
            }
            check(startupError.isBlank()) { startupError }
            check(serverPort in 1024..65535 && remotePid > 0 &&
                remotePid != Process.myPid()) {
                "Второй VPN-движок не изолирован в отдельном процессе"
            }
            active = true
            return serverPort
        } catch (e: Exception) {
            stop()
            throw IllegalStateException(e.message ?: "Не удалось запустить sing-box", e)
        } finally {
            pending = null
        }
    }

    fun stop() {
        if (closed) return
        closed = true
        active = false
        pending?.countDown()
        runCatching { remote?.send(Message.obtain(null, MSG_STOP)) }
        remote = null
        if (bound) {
            bound = false
            connection?.let { runCatching {
                context.applicationContext.unbindService(it)
            } }
        }
        connection = null
    }
}
