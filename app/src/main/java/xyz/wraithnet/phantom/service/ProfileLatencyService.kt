package xyz.wraithnet.phantom.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.wraithnet.phantom.data.XrayConfigBuilder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val CONFIG = "config"
private const val TARGET = "target"
private const val DEADLINE = "deadline"
private const val RESULT = "result"

/** Bound only for a measurement. The VPN's native processes and TUN stay untouched. */
abstract class ProfileLatencyService(parallelism: Int) : Service() {
    private val worker = Executors.newFixedThreadPool(parallelism)
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { handleRequest(it) })

    @Suppress("DEPRECATION")
    private fun handleRequest(message: Message): Boolean {
        val data = message.data
        val descriptor = data.getParcelable<ParcelFileDescriptor>(CONFIG) ?: return true
        val target = data.getString(TARGET).orEmpty()
        val deadline = data.getLong(DEADLINE)
        val reply = message.replyTo
        if (!postToWorker {
            val result = runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                    if (SystemClock.elapsedRealtime() >= deadline) return@runCatching -1L
                    measure(reader.readText(), target, deadline)
                }
            }.getOrDefault(-1L)
            runCatching {
                reply?.send(Message.obtain().apply { this.data = Bundle().apply { putLong(RESULT, result) } })
            }
        }) descriptor.close()
        return true
    }

    protected fun postToWorker(block: () -> Unit): Boolean = try {
        worker.execute { block() }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    protected abstract fun measure(config: String, target: String, deadline: Long): Long

    override fun onBind(intent: Intent): IBinder = inbox.binder

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

class XrayLatencyService : ProfileLatencyService(3) {
    override fun measure(config: String, target: String, deadline: Long): Long {
        XrayEnvironment.initialize(this, probeOnly = true)
        val password = UUID.randomUUID().toString()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(code: Long, message: String) = 0L
        })
        try {
            core.startLoop(XrayConfigBuilder.forLatency(config, port, password), 0)
            return measureHttp(port, password, target, deadline)
        } finally {
            core.stopLoop()
        }
    }
}

class SingBoxLatencyService : ProfileLatencyService(1) {
    private companion object {
        val nativeSession = Any()
    }

    override fun measure(config: String, target: String, deadline: Long): Long = synchronized(nativeSession) {
        if (SystemClock.elapsedRealtime() >= deadline) return@synchronized -1L
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@synchronized -1L
        if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != false) {
            return@synchronized -1L
        }
        val core = SingBoxNativeBackend(this, ::postToWorker, { fd ->
            runCatching {
                ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
            }.isSuccess
        }, "singbox-latency")
        val password = UUID.randomUUID().toString()
        try {
            val port = core.start(config, password)
            measureHttp(port, password, target, deadline)
        } finally {
            core.stop()
        }
    }
}

private val http = OkHttpClient.Builder()
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

private fun measureHttp(port: Int, password: String, target: String, deadline: Long): Long {
    val remaining = deadline - SystemClock.elapsedRealtime()
    if (remaining <= 0L) return -1L
    val client = http.newBuilder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic("phantom", password)).build()
        }
        .callTimeout(remaining, TimeUnit.MILLISECONDS)
        .build()
    val request = Request.Builder().url(target).header("Connection", "close").build()
    val started = System.nanoTime()
    return client.newCall(request).execute().use {
        if (it.code != 204) -1L
        else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1L)
    }
}

internal object ProfileLatencyClient {
    // libbox's CommandServer owns native process resources. Start the timeout
    // after obtaining a slot, so queued profiles get their own full budget.
    private val singBoxSlot = Semaphore(1)

    suspend fun measure(
        context: Context, config: String, singBox: Boolean, target: String, timeout: Int,
    ): Long? = if (singBox) singBoxSlot.withPermit {
        measureBound(context, config, true, target, timeout)
    } else measureBound(context, config, false, target, timeout)

    private suspend fun measureBound(
        context: Context, config: String, singBox: Boolean, target: String, timeout: Int,
    ): Long? = withContext(Dispatchers.IO) {
        // Large provider configs can exceed the Binder transaction limit.
        // Send a read-only descriptor to an unlinked app-private file instead.
        val temporary = File.createTempFile("latency-", ".json", context.cacheDir)
        val descriptor = try {
            temporary.writeText(config)
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            temporary.delete()
        }
        descriptor.use {
            withContext(Dispatchers.Main.immediate) {
                val service = if (singBox) SingBoxLatencyService::class.java else XrayLatencyService::class.java
                var binding: ServiceConnection? = null
                try {
                    withTimeoutOrNull(timeout.toLong()) {
                        val deadline = SystemClock.elapsedRealtime() + timeout
                        suspendCancellableCoroutine<Long?> { task ->
                            val connection = object : ServiceConnection {
                                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                    if (!task.isActive) return
                                    val reply = Messenger(Handler(Looper.getMainLooper()) { result ->
                                        if (task.isActive) {
                                            task.resume(result.data.getLong(RESULT).takeIf { it > 0L })
                                        }
                                        true
                                    })
                                    val message = Message.obtain().apply {
                                        replyTo = reply
                                        data = Bundle().apply {
                                            putParcelable(CONFIG, descriptor)
                                            putString(TARGET, target)
                                            putLong(DEADLINE, deadline)
                                        }
                                    }
                                    if (runCatching { Messenger(binder).send(message) }.isFailure && task.isActive) {
                                        task.resume(null)
                                    }
                                }
                                override fun onServiceDisconnected(name: ComponentName) {
                                    if (task.isActive) task.resume(null)
                                }
                                override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                                override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                            }
                            if (context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)) {
                                binding = connection
                            } else task.resume(null)
                        }
                    }
                } finally {
                    binding?.let(context::unbindService)
                }
            }
        }
    }
}
