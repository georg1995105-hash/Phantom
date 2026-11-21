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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.wraithnet.phantom.data.TcpEndpoint
import xyz.wraithnet.phantom.data.XrayConfigBuilder
import java.io.File
import java.io.IOException
import org.json.JSONObject
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
private const val SETUP_DEADLINE = "setupDeadline"
private const val NETWORK_TIMEOUT = "networkTimeout"
private const val SETUP_TIMEOUT_MS = 8_000L
private const val RESULT = "result"
private const val LOG_TAG = "PhantomLatency"

private const val TCP_HOST = "tcpHost"
private const val TCP_PORT = "tcpPort"

private fun reading(delay: Long, peer: TcpEndpoint? = null): Bundle = Bundle().apply {
    putLong(RESULT, delay)
    if (peer != null) {
        putString(TCP_HOST, peer.host)
        putInt(TCP_PORT, peer.port)
    }
}

internal data class ProfileTiming(val milliseconds: Long, val tcpPeer: TcpEndpoint?)

/** Bound only for a measurement. The VPN's native processes and TUN stay untouched. */
abstract class ProfileLatencyService(parallelism: Int) : Service() {
    private val worker = Executors.newFixedThreadPool(parallelism)
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { handleRequest(it) })

    @Suppress("DEPRECATION")
    private fun handleRequest(message: Message): Boolean {
        val data = message.data
        val descriptor = data.getParcelable<ParcelFileDescriptor>(CONFIG) ?: return true
        val target = data.getString(TARGET).orEmpty()
        val deadline = data.getLong(SETUP_DEADLINE)
        val networkTimeout = data.getInt(NETWORK_TIMEOUT)
        val reply = message.replyTo
        if (!postToWorker {
            val result = runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                    if (SystemClock.elapsedRealtime() >= deadline) return@runCatching null
                    measure(reader.readText(), target, deadline, networkTimeout)
                }
            }.getOrElse {
                Log.d(LOG_TAG, "${javaClass.simpleName}: ${it.javaClass.simpleName}")
                null
            }
            runCatching {
                reply?.send(Message.obtain().apply { this.data = result ?: reading(-1L) })
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

    protected abstract fun measure(config: String, target: String, deadline: Long, networkTimeout: Int): Bundle?

    override fun onBind(intent: Intent): IBinder = inbox.binder

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

class XrayLatencyService : ProfileLatencyService(3) {
    override fun measure(config: String, target: String, deadline: Long, networkTimeout: Int): Bundle? {
        val manager = getSystemService(ConnectivityManager::class.java)
        val underlay = manager.activeNetwork ?: return null
        // Phantom excludes its UID from its TUN. The pinned Xray wrapper has no fd binding
        // hook for an unrelated VPN that captures this UID. A nested result is misleading.
        if (manager.getNetworkCapabilities(underlay)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true)
            return null
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
            if (SystemClock.elapsedRealtime() >= deadline) return null
            val json = JSONObject(config)
            val observing = json.has("observatory") || json.has("burstObservatory")
            val until = SystemClock.elapsedRealtime() + networkTimeout
            var pause = 150L
            do {
                val remaining = (until - SystemClock.elapsedRealtime()).toInt()
                if (remaining <= 0) return null
                core.queryAllOutboundTrafficStats()
                val delay = try { measureHttp(port, password, target, remaining) } catch (_: IOException) { -1L }
                if (delay > 0) {
                    if (manager.activeNetwork != underlay) return null
                    val peer = TcpEndpoint.observed(json, core.queryAllOutboundTrafficStats())
                    return reading(delay, peer)
                }
                if (!observing) return null
                // A cold leastPing/leastLoad pool may not have its first observation yet.
                Thread.sleep(minOf(pause, (until - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
                pause = (pause * 2).coerceAtMost(800L)
            } while (SystemClock.elapsedRealtime() < until)
            return null
        } finally {
            core.stopLoop()
        }
    }
}

class SingBoxLatencyService : ProfileLatencyService(1) {
    private companion object {
        val nativeSession = Any()
    }

    override fun onDestroy() {
        // libbox owns a process-wide Go runtime. Reopening a CommandServer in
        // the same Linux process after stop() may race native callbacks (the
        // actual VPN worker follows the same one-session-per-process rule).
        // This service runs ONLY in :singbox_latency. The caller unbinds after
        // receiving its Messenger reply, so retire this isolated probe process
        // rather than reusing stale JNI state for the next TUIC ping.
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun measure(config: String, target: String, deadline: Long, networkTimeout: Int): Bundle? = synchronized(nativeSession) {
        if (SystemClock.elapsedRealtime() >= deadline) return@synchronized null
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.physicalNetwork() ?: return@synchronized null
        // Native Go sockets need an explicit fd binding, even when another VPN is active.
        val core = SingBoxNativeBackend(this, ::postToWorker, protectSocket = { fd ->
            ParcelFileDescriptor.fromFd(fd).use { duplicate -> network.bindSocket(duplicate.fileDescriptor) }
            true
        }, dataDirectory = "singbox-latency")
        val password = UUID.randomUUID().toString()
        try {
            val port = core.start(config, password, network)
            if (SystemClock.elapsedRealtime() >= deadline) return@synchronized null
            reading(measureHttp(port, password, target, networkTimeout))
        } finally {
            core.stop()
        }
    }
}

private val http = OkHttpClient.Builder()
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

internal fun measureHttp(port: Int, password: String, target: String, timeout: Int, expectedStatus: Int = 204): Long {
    val url = target.toHttpUrl()
    val authorization = Credentials.basic("phantom", password)
    val client = http.newBuilder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder()
                .header("Proxy-Authorization", authorization).build()
        }
        .callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
        .build()
    val request = Request.Builder().url(url).header("Connection", "close").apply {
        // HTTPS authenticates on CONNECT; never forward local proxy credentials to the origin.
        if (!url.isHttps) header("Proxy-Authorization", authorization)
    }.build()
    val started = System.nanoTime()
    return client.newCall(request).execute().use {
        if (it.code != expectedStatus) {
            Log.d(LOG_TAG, "Probe HTTP status ${it.code}")
            -1L
        }
        else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1L)
    }
}

internal object ProfileLatencyClient {
    // libbox's CommandServer owns native process resources. Start the timeout
    // after obtaining a slot, so queued profiles get their own full budget.
    private val singBoxSlot = Semaphore(1)

    suspend fun measure(
        context: Context, config: String, singBox: Boolean, target: String, timeout: Int,
    ): ProfileTiming? = if (singBox) singBoxSlot.withPermit {
        measureBound(context, config, true, target, timeout)
    } else measureBound(context, config, false, target, timeout)

    private suspend fun measureBound(
        context: Context, config: String, singBox: Boolean, target: String, timeout: Int,
    ): ProfileTiming? = withContext(Dispatchers.IO) {
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
                    withTimeoutOrNull(SETUP_TIMEOUT_MS + timeout) {
                        val deadline = SystemClock.elapsedRealtime() + SETUP_TIMEOUT_MS
                        suspendCancellableCoroutine<ProfileTiming?> { task ->
                            val connection = object : ServiceConnection {
                                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                    if (!task.isActive) return
                                    val reply = Messenger(Handler(Looper.getMainLooper()) { result ->
                                        if (task.isActive) {
                                            val delay = result.data.getLong(RESULT)
                                            val peer = result.data.getString(TCP_HOST)?.let { TcpEndpoint(it, result.data.getInt(TCP_PORT)) }
                                            task.resume(if (delay > 0) ProfileTiming(delay, peer) else null)
                                        }
                                        true
                                    })
                                    val message = Message.obtain().apply {
                                        replyTo = reply
                                        data = Bundle().apply {
                                            putParcelable(CONFIG, descriptor)
                                            putString(TARGET, target)
                                            putLong(SETUP_DEADLINE, deadline)
                                            putInt(NETWORK_TIMEOUT, timeout)
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
