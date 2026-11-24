package xyz.wraithnet.phantom.service

import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import xyz.wraithnet.phantom.data.ActiveVpnSubscriptionProxy
import xyz.wraithnet.phantom.data.AppRouting
import xyz.wraithnet.phantom.data.FragmentationOptions
import xyz.wraithnet.phantom.data.TrafficBytes
import xyz.wraithnet.phantom.data.TrafficRates
import xyz.wraithnet.phantom.data.VpnDnsSettings
import xyz.wraithnet.phantom.data.VpnEventLog
import xyz.wraithnet.phantom.data.ConnectivityProbePlan
import xyz.wraithnet.phantom.data.XrayConfigBuilder
import xyz.wraithnet.phantom.data.VpnProfilePreflight
import xyz.wraithnet.phantom.R
import xyz.wraithnet.phantom.MainActivity

class PhantomVpnService : VpnService() {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    // Intent commands may launch on different IO threads; the latest tap wins.
    private val commandEpoch = AtomicLong(0)
    private lateinit var prefs: SharedPreferences
    private var vpnInterface: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var singBoxBridge: SingBoxBridge? = null
    private var singBoxSession: Any? = null
    private var singBoxPinnedPort: Int? = null
    private var singBoxRaw: String? = null
    private var singBoxRecoveryJob: Job? = null
    private var singBoxRecoveryAttempts = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionBytes: TrafficBytes? = null
    private var trafficJob: Job? = null
    private var verificationJob: Job? = null
    private var verificationSession: Any? = null
    // Store only in memory while this native session is alive; never persist credentials.
    private data class VerificationTarget(
        val config: String,
        val port: Int,
        val password: String,
        val singBoxPort: Int?,
    )
    private var verificationTarget: VerificationTarget? = null
    private var handoverJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastPhysicalRoute: String? = null

    @Volatile private var running = false
    @Volatile private var state = STATUS_DISCONNECTED
    @Volatile private var lastStatusError = ""
    @Volatile private var revokedByAndroid = false

    override fun onCreate() {
        super.onCreate()
        VpnRuntimeBridge.publish(STATUS_DISCONNECTED)
        prefs = getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Phantom VPN"))
        observePhysicalNetworks()
    }

    /**
     * The app's UID is excluded from TUN. Its Android default network is the
     * same route that the core's outbound sockets use.
     */
    private fun physicalRoute(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(network) ?: return null
        // NET_CAPABILITY_VALIDATED is Android's external Internet test, not
        // proof the requested VPN server is reachable. Requiring it prevented
        // boot recovery on usable but unvalidated/censored mobile networks.
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        return network.toString()
    }

    private fun scheduleNetworkCheck() {
        handoverJob?.cancel()
        handoverJob = ioScope.launch {
            delay(1600L)
            mutex.withLock {
                if (revokedByAndroid) return@withLock
                val route = runCatching { physicalRoute() }.getOrNull()
                when (PhysicalHandoverPolicy.decide(
                    requested = prefs.getBoolean("vpn_requested", false),
                    running = running,
                    currentRoute = route,
                    previousRoute = lastPhysicalRoute,
                )) {
                    PhysicalHandoverPolicy.Decision.IGNORE -> Unit
                    PhysicalHandoverPolicy.Decision.NETWORK_LOST -> {
                        // Fail closed: retain TUN while the physical route is unavailable.
                        lastPhysicalRoute = null
                        verificationSession = null
                        verificationJob?.cancel()
                        setState(STATUS_DEGRADED, "Нет физического подключения к сети")
                        updateNotification("VPN · ожидание сети")
                    }
                    PhysicalHandoverPolicy.Decision.PRESERVE_TUN -> {
                        lastPhysicalRoute = route
                        // The old HTTPS result cannot prove the new network's egress.
                        setState(STATUS_DEGRADED, "Проверка VPN после смены сети")
                        updateNotification("VPN · проверка после смены сети")
                        if (singBoxPinnedPort != null && singBoxSession == null) {
                            scheduleSingBoxRecovery()
                        } else {
                            verifyCurrentTunnel()
                        }
                    }
                    PhysicalHandoverPolicy.Decision.BOOTSTRAP -> {
                        val profile = prefs.getString("vpn_last_profile", "").orEmpty()
                        if (profile.isNotBlank()) startLocked(profile, commandEpoch.get())
                    }
                }
            }
        }
    }

    private fun observePhysicalNetworks() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkCheck()
            override fun onLost(network: Network) = scheduleNetworkCheck()
            override fun onCapabilitiesChanged(
                network: Network, capabilities: NetworkCapabilities
            ) = scheduleNetworkCheck()
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val epoch = commandEpoch.incrementAndGet()
                prefs.edit().putBoolean("vpn_requested", false).apply()
                handoverJob?.cancel()
                ioScope.launch {
                    mutex.withLock {
                        if (epoch != commandEpoch.get()) return@withLock
                        stopLocked()
                    }
                    if (epoch == commandEpoch.get() &&
                        !prefs.getBoolean("vpn_requested", false)
                    ) stopSelfResult(startId)
                }
            }
            ACTION_QUERY -> sendStatus(state)
            ACTION_TRAFFIC_ON -> ioScope.launch {
                mutex.withLock { if (running) startTrafficReports() }
            }
            ACTION_TRAFFIC_OFF -> ioScope.launch {
                mutex.withLock {
                    trafficJob?.cancel()
                    trafficJob = null
                }
            }
            ACTION_RELOAD -> {
                val epoch = commandEpoch.incrementAndGet()
                ioScope.launch { mutex.withLock {
                    if (epoch == commandEpoch.get()) {
                        startLocked(prefs.getString("vpn_last_profile", "").orEmpty(), epoch)
                    }
                } }
            }
            ACTION_KEEP_ALIVE -> ioScope.launch {
                mutex.withLock { configureWakeLock() }
            }
            ACTION_START -> {
                val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                // Empty START is a rejected replacement, not a service state change.
                val epoch = commandEpoch.incrementAndGet()
                prefs.edit().putBoolean("vpn_requested", true).apply()
                ioScope.launch { mutex.withLock {
                    if (epoch == commandEpoch.get()) startLocked(profile, epoch)
                } }
            }
            ACTION_BOOT_START -> {
                val last = prefs.getString("vpn_last_profile", "").orEmpty()
                if (last.isNotBlank() && VpnService.prepare(this) == null) {
                    prefs.edit().putBoolean("vpn_requested", true).apply()
                    setState(STATUS_STARTING)
                    updateNotification("Ожидание сети после включения телефона")
                    scheduleNetworkCheck()
                } else stopSelf()
            }
            else -> {
                val last = prefs.getString("vpn_last_profile", "").orEmpty()
                val alwaysOn = Build.VERSION.SDK_INT >= 29 && isAlwaysOn
                if (last.isNotBlank() && (alwaysOn ||
                    prefs.getBoolean("vpn_requested", false))) {
                    prefs.edit().putBoolean("vpn_requested", true).apply()
                    scheduleNetworkCheck()
                } else {
                    stopSelf()
                }
            }
        }
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private fun startLocked(raw: String, command: Long? = null) {
        fun commandCurrent() = !revokedByAndroid &&
            (command == null || command == commandEpoch.get())
        if (!commandCurrent()) return
        // Resolve cross-process settings BEFORE touching an existing live tunnel.
        // A dead UI/settings process must not turn a working VPN into an outage.
        val settings = try {
            VpnSettingsBridge.read(this)
        } catch (_: Exception) {
            if (!commandCurrent()) return
            if (running) rejectLiveReplacement("Новые настройки VPN не получены")
            else setState(STATUS_ERROR, "Не удалось получить настройки VPN")
            return
        }
        if (!commandCurrent()) return
        // A malformed replacement profile must NEVER tear down a healthy tunnel.
        // Validate the entire Java-side adapter before stopping the old Xray/TUN.
        val prepared = try {
            VpnProfilePreflight.prepare(raw, settings.dns, settings.fragmentation)
        } catch (e: Exception) {
            if (!commandCurrent()) return
            val reason = e.message ?: "Некорректный профиль VPN"
            if (running) {
                rejectLiveReplacement(reason)
            } else {
                prefs.edit().putBoolean("vpn_requested", false).apply()
                setState(STATUS_ERROR, reason)
            }
            return
        }
        // STOP/revoke/new START can arrive during settings IPC or preflight.
        // Do not tear down the existing TUN for a superseded command.
        if (!commandCurrent()) return
        val previousClosed = stopLocked(false)
        setState(STATUS_STARTING)
        try {
            check(previousClosed) {
                "Старое ядро sing-box не завершилось. Подключение остановлено во избежание сбоя"
            }
            val scheme = prepared.scheme
            val dns = settings.dns
            var singBoxPort: Int? = null
            val baseConfig = if (prepared.usesSingBox) {
                // sing-box handles the native TUIC/AnyTLS transport, while Xray
                // keeps Android TUN and all existing per-app VPN routing.
                val session = Any()
                singBoxSession = session
                val engine = SingBoxBridge(this) {
                    onSingBoxUnexpectedExit(session)
                }
                singBoxBridge = engine
                // Validate only local SOCKS readiness before Android TUN exists.
                // The first outbound TUIC/AnyTLS dial must happen after the VPN
                // interface and native Xray routing are established.
                val socksPort = engine.start(raw)
                singBoxPort = socksPort
                XrayConfigBuilder.buildSocksBridge(socksPort)
            } else {
                checkNotNull(prepared.xrayConfig)
            }
            val config = dns.applyToXray(settings.fragmentation.apply(baseConfig))
            val providerDns = if (scheme == "xray" && dns.mode == VpnDnsSettings.Mode.DEFAULT)
                XrayConfigBuilder.withProviderTunDns(config) else null
            val systemDns = if (providerDns == null) dns.addresses() else listOf(XrayConfigBuilder.TUN_RESOLVER)
            // The VPN's own sockets must not recursively enter the VPN TUN.
            // The app is intentionally excluded; user apps still route via VPN.
            val tun = Builder()
                .setSession("Phantom")
                .setMtu(1400)
                .addAddress("10.111.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1111::2", 64)
                .addRoute("::", 0)
                .also { builder ->
                    systemDns.forEach { builder.addDnsServer(it) }
                    AppRouting.apply(this, builder, settings.routeMode, settings.applications)
                }
                .establish() ?: error("Android не создал VPN-интерфейс")
            vpnInterface = tun

            XrayEnvironment.initialize(applicationContext)
            val active = Libv2ray.newCoreController(callback)
            controller = active
            // Provide an authenticated loopback HTTP inbound on every transport,
            // including TUIC/AnyTLS. Phantom's UID remains outside Android's TUN;
            // only explicit manual subscription requests use this proxy.
            val subscriptionProxyPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                .use { it.localPort }
            val password = UUID.randomUUID().toString()
            val runningConfig = XrayConfigBuilder.withConnectivityCheck(
                providerDns ?: config, subscriptionProxyPort, password)
            active.startLoop(runningConfig, tun.fd)

            // HTTPS verification must not hold the VPN lifecycle mutex. The
            // TUN and native core are already installed; verification below
            // runs in a cancellable background session after startup returns.
            check(!revokedByAndroid) { "Android переключил VPN на другое приложение" }
            check(command == null || command == commandEpoch.get()) {
                "Подключение заменено новым действием"
            }
            sessionBytes = TrafficBytes()
            running = true
            singBoxRaw = if (singBoxPort != null) raw else null
            singBoxPinnedPort = singBoxPort
            singBoxRecoveryAttempts = 0
            ActiveVpnSubscriptionProxy.publish(subscriptionProxyPort, password, raw)
            prefs.edit().putString("vpn_last_profile", raw)
                .putBoolean("vpn_running", true)
                .putLong("vpn_connected_since_elapsed", SystemClock.elapsedRealtime()).apply()
            configureWakeLock(settings.keepActive)
            lastPhysicalRoute = runCatching { physicalRoute() }.getOrNull()
            setState(STATUS_DEGRADED)
            updateNotification("VPN · проверка соединения")
            if (settings.uiVisible) startTrafficReports()
            verificationTarget = VerificationTarget(
                runningConfig, subscriptionProxyPort, password, singBoxPort)
            verifyCurrentTunnel()
        } catch (e: Exception) {
            stopLocked(false)
            if (revokedByAndroid) setState(STATUS_DISCONNECTED)
            else setState(STATUS_ERROR, e.message ?: "Не удалось подключиться")
            updateNotification("Ошибка подключения")
        }
    }



    /** Called under the lifecycle mutex after a rejected START/RELOAD. */
    private fun rejectLiveReplacement(reason: String) {
        sendStatus(state, reason)
        // Both settings IPC failure and profile rejection advance commandEpoch.
        // Re-arm work for the original session; never restart Xray or Android TUN.
        if (singBoxPinnedPort != null && singBoxSession == null) {
            singBoxRecoveryJob?.cancel()
            singBoxRecoveryJob = null
            scheduleSingBoxRecovery()
        } else if (state == STATUS_DEGRADED &&
            runCatching { physicalRoute() }.getOrNull() != null) {
            verifyCurrentTunnel()
        }
    }

    /**
     * Only the current worker may signal its death. Keep the existing Xray
     * TUN and revoke stale HTTPS proof before attempting recovery.
     */
    private fun onSingBoxUnexpectedExit(session: Any) {
        ioScope.launch {
            mutex.withLock {
                if (singBoxSession !== session) return@withLock
                singBoxSession = null
                val oldBridge = singBoxBridge
                singBoxBridge = null
                val retired = runCatching { oldBridge?.stop() }.isSuccess
                if (running && controller != null && vpnInterface != null && !revokedByAndroid) {
                    verificationSession = null
                    verificationJob?.cancel()
                    setState(STATUS_DEGRADED, "Процесс sing-box завершился")
                    updateNotification("VPN · восстановление транспорта")
                    if (retired) scheduleSingBoxRecovery()
                    else {
                        singBoxRecoveryAttempts = SingBoxRecoveryPolicy.MAX_ATTEMPTS
                        setState(STATUS_DEGRADED,
                            "Старый sing-box не завершён; требуется ручное переподключение")
                    }
                } else if (!revokedByAndroid) {
                    stopLocked(false)
                    setState(STATUS_ERROR, "Процесс sing-box завершился при запуске")
                    updateNotification("Ошибка движка VPN")
                }
            }
        }
    }

    /** Call under the VPN lifecycle mutex only. */
    private fun scheduleSingBoxRecovery() {
        if (singBoxRecoveryJob?.isActive == true ||
            singBoxRecoveryAttempts >= SingBoxRecoveryPolicy.MAX_ATTEMPTS) return
        if (singBoxRaw.isNullOrBlank() || singBoxPinnedPort == null ||
            runCatching { physicalRoute() }.getOrNull() == null) return
        val epoch = commandEpoch.get()
        singBoxRecoveryJob = ioScope.launch { recoverSingBox(epoch) }
    }

    private fun canRecoverSingBox(epoch: Long): Boolean = SingBoxRecoveryPolicy.canAttempt(
        running = running,
        requested = prefs.getBoolean("vpn_requested", false),
        revoked = revokedByAndroid,
        sameEpoch = epoch == commandEpoch.get(),
        coreAndTunAlive = controller != null && vpnInterface != null,
        workerAbsent = singBoxSession == null,
        hasProfileAndPinnedPort = !singBoxRaw.isNullOrBlank() && singBoxPinnedPort != null,
        hasPhysicalNetwork = runCatching { physicalRoute() }.getOrNull() != null,
        attempts = singBoxRecoveryAttempts,
    )

    /**
     * Bounded recovery on a fresh :singbox Go process and EXACTLY the old
     * local SOCKS port. Never destroys Xray or TUN. An explicit command
     * invalidates the epoch and cancels all pending attempts.
     */
    private suspend fun recoverSingBox(epoch: Long) {
        while (true) {
            val nextAttempt = mutex.withLock {
                if (canRecoverSingBox(epoch)) singBoxRecoveryAttempts else null
            } ?: return
            delay(SingBoxRecoveryPolicy.delayBeforeAttemptMs(nextAttempt))
            val outcome = mutex.withLock {
                if (!canRecoverSingBox(epoch)) return@withLock 0
                singBoxRecoveryAttempts++
                val raw = checkNotNull(singBoxRaw)
                val pinnedPort = checkNotNull(singBoxPinnedPort)
                val session = Any()
                val bridge = SingBoxBridge(this@PhantomVpnService) {
                    onSingBoxUnexpectedExit(session)
                }
                singBoxSession = session
                singBoxBridge = bridge
                try {
                    val restoredPort = bridge.start(raw, pinnedPort)
                    check(restoredPort == pinnedPort) {
                        "sing-box изменил порт, закреплённый за Xray"
                    }
                    check(!revokedByAndroid && epoch == commandEpoch.get() &&
                        prefs.getBoolean("vpn_requested", false)) {
                        "Сеанс VPN заменён новой командой"
                    }
                    setState(STATUS_DEGRADED, "Транспорт восстановлен; проверка HTTPS")
                    updateNotification("VPN · проверка после восстановления")
                    verifyCurrentTunnel()
                    // Release the completed retry owner while still holding
                    // the mutex. A worker that crashes immediately afterwards
                    // must be allowed to schedule its next bounded attempt.
                    singBoxRecoveryJob = null
                    1
                } catch (_: Exception) {
                    if (singBoxSession === session) {
                        singBoxSession = null
                        singBoxBridge = null
                    }
                    val exited = runCatching { bridge.stop() }.isSuccess
                    // Android revoke and queued STOP/RELOAD win even if a native
                    // Binder start finished while the lifecycle mutex was held.
                    if (!running || revokedByAndroid || epoch != commandEpoch.get() ||
                        !prefs.getBoolean("vpn_requested", false)) return@withLock 0
                    if (!exited) singBoxRecoveryAttempts = SingBoxRecoveryPolicy.MAX_ATTEMPTS
                    val exhausted = singBoxRecoveryAttempts >= SingBoxRecoveryPolicy.MAX_ATTEMPTS
                    setState(STATUS_DEGRADED, if (!exited)
                        "Старый процесс sing-box не завершился; автоматический запуск остановлен"
                    else if (exhausted)
                        "sing-box не восстановлен за три попытки; требуется ручное подключение"
                    else "Повторная попытка восстановления sing-box")
                    updateNotification(if (exhausted) "VPN · восстановление не удалось"
                        else "VPN · повторное восстановление")
                    2
                }
            }
            if (outcome != 2) return
        }
    }

    /** Invalidate previous proof and verify through the live TUN's local route. */
    private fun verifyCurrentTunnel() {
        val target = verificationTarget ?: return
        val session = Any()
        verificationSession = session
        scheduleVerification(session, target.config, target.port, target.password,
            target.singBoxPort, commandEpoch.get())
    }

    /**
     * Probes an authenticated inbound which inherits the Android TUN routing
     * policy. Never hold [mutex] while external websites are unavailable:
     * STOP, profile changes, and worker failure must remain responsive.
     *
     * All outbound failures leave the native TUN installed (fail-closed). A
     * result is published only for the exact live session and command epoch;
     * stale replies after STOP/RELOAD can never resurrect Connected.
     */
    private fun scheduleVerification(
        session: Any,
        config: String,
        port: Int,
        password: String,
        singBoxPort: Int?,
        epoch: Long,
    ) {
        verificationJob?.cancel()
        verificationJob = ioScope.launch {
            val checks = runCatching { ConnectivityProbePlan.forConfig(config) }
                .getOrDefault(emptyList())
            var verified = false
            var checked = 0
            var failure = "контрольный HTTPS-адрес не подтвердил ответ"
            for (step in checks) {
                if (!isActive || revokedByAndroid || epoch != commandEpoch.get()) return@launch
                if (step.pauseBeforeMs > 0L) delay(step.pauseBeforeMs)
                if (!isActive || revokedByAndroid || epoch != commandEpoch.get()) return@launch
                checked++
                val result = runCatching {
                    measureHttp(port, password, step.url, step.timeoutMs,
                        expectedStatus = step.expectedStatus)
                }
                if ((result.getOrNull() ?: -1L) > 0L) {
                    verified = true
                    break
                }
                // No URLs, credentials, or arbitrary native exception messages.
                failure = when (val error = result.exceptionOrNull()) {
                    is java.net.SocketTimeoutException,
                    is java.io.InterruptedIOException -> "тайм-аут HTTPS"
                    is java.net.UnknownHostException -> "ошибка DNS"
                    is javax.net.ssl.SSLException -> "ошибка TLS"
                    is java.net.ConnectException -> "локальный HTTP-прокси не отвечает"
                    is java.io.IOException -> "обрыв HTTPS-соединения"
                    null -> "неверный ответ контрольного HTTPS-адреса"
                    else -> "ошибка проверки HTTPS"
                }
            }
            if (!isActive || revokedByAndroid || epoch != commandEpoch.get()) return@launch
            val diagnosis = if (verified) "" else if (singBoxPort != null) {
                // Native worker health is a separate signal. Avoid another
                // long external probe on the startup critical path.
                val local = LocalSocks5Probe.check(singBoxPort)
                if (!local.ready) "Локальный SOCKS5 sing-box недоступен: " +
                    (local.reason ?: "не отвечает")
                else "TUIC/AnyTLS и Xray запущены, но HTTPS через VPN не подтвердился " +
                    "($checked проверок; $failure)"
            } else "Xray запущен, но HTTPS через VPN не подтвердился " +
                "($checked проверок; $failure)"
            mutex.withLock {
                if (!VpnVerificationPolicy.canCommit(
                    running = running,
                    revoked = revokedByAndroid,
                    sessionMatches = verificationSession === session,
                    epochMatches = epoch == commandEpoch.get(),
                    jobActive = isActive,
                )) return@withLock
                setState(VpnTunnelHealth.afterHttpsProbes(verified), diagnosis)
                updateNotification(if (verified) "VPN подключён" else "VPN · связь не подтверждена")
            }
        }
    }

    /**
     * Tear down the dependency chain in order: isolated sing-box -> Xray -> TUN.
     * The next profile must not begin until the former Go worker has exited.
     */
    private fun stopLocked(report: Boolean = true): Boolean {
        singBoxRecoveryJob?.cancel()
        singBoxRecoveryJob = null
        singBoxRecoveryAttempts = 0
        singBoxRaw = null
        singBoxPinnedPort = null
        verificationSession = null
        verificationTarget = null
        verificationJob?.cancel()
        verificationJob = null
        ActiveVpnSubscriptionProxy.clear()
        running = false
        trafficJob?.cancel()
        trafficJob = null
        releaseWakeLock()
        singBoxSession = null
        val previousBridge = singBoxBridge
        singBoxBridge = null
        val workerStopped = runCatching { previousBridge?.stop() }.isSuccess
        runCatching { controller?.stopLoop() }
        controller = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        prefs.edit().putBoolean("vpn_running", false)
            .putLong("vpn_connected_since_elapsed", 0L).apply()
        if (report) {
            setState(STATUS_DISCONNECTED)
            updateNotification("VPN отключён")
        }
        return workerStopped
    }

    /** Only the running Xray core is queried; no root access, server API or device-wide counters. */
    private fun startTrafficReports() {
        trafficJob?.cancel()
        trafficJob = ioScope.launch {
            var lastAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1500L)
                mutex.withLock {
                    if (!running) return@withLock
                    val report = runCatching {
                        controller?.queryAllOutboundTrafficStats()
                    }.getOrNull()
                    val now = SystemClock.elapsedRealtime()
                    val delta = report?.let(TrafficRates::bytesFromReport)
                    sessionBytes = delta?.let { sessionBytes?.plus(it) }
                    val rate = delta?.rate(now - lastAt)
                    lastAt = now
                    sendBroadcast(
                        Intent(ACTION_TRAFFIC).setPackage(packageName)
                            .putExtra(EXTRA_DOWNLOAD_BYTES, sessionBytes?.download ?: -1L)
                            .putExtra(EXTRA_UPLOAD_BYTES, sessionBytes?.upload ?: -1L)
                            .putExtra(EXTRA_DOWNLOAD_BPS, rate?.downloadBytesPerSecond ?: -1L)
                            .putExtra(EXTRA_UPLOAD_BPS, rate?.uploadBytesPerSecond ?: -1L)
                    )
                }
            }
        }
    }

    private fun configureWakeLock(enabled: Boolean = VpnSettingsBridge.read(this).keepActive) {
        releaseWakeLock()
        if (!running || !enabled) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:vpn-keep-active").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String): Long {
            // The synchronous start/verification path determines actual UI state.
            return 0
        }
    }

    private fun setState(next: String, error: String = "") {
        // Persist and publish the real service state BEFORE asking SystemUI to redraw.
        state = next
        lastStatusError = error
        prefs.edit().putString("vpn_status", next).apply()
        VpnRuntimeBridge.publish(next,
            if (isTunnelActive(next))
                prefs.getLong("vpn_connected_since_elapsed", 0L) else 0L)
        VpnEventLog.record(applicationContext, next, error)
        sendStatus(next, error)
        PhantomQuickTileService.notifyStateChanged(applicationContext)
    }

    private fun sendStatus(status: String, error: String = lastStatusError) {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATUS, status).putExtra(EXTRA_ERROR, error)
                .putExtra(EXTRA_CONNECTED_SINCE,
                    if (isTunnelActive(status)) prefs.getLong("vpn_connected_since_elapsed", 0L) else 0L)
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phantom VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 11,
            Intent(this, PhantomVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phantom VPN")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_phantom)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (running) {
            builder.addAction(Notification.Action.Builder(
                R.drawable.ic_stat_phantom, "Отключить", stop
            ).build())
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onRevoke() {
        // Android invokes this when another VPN (such as INCY) takes ownership.
        // Publish the revoked state *before* super.onRevoke() stops the service:
        // deferred coroutines can be cancelled by onDestroy before they run.
        revokedByAndroid = true
        commandEpoch.incrementAndGet()
        // The revoked service must ignore late worker-death Binder callbacks.
        singBoxSession = null
        singBoxRecoveryJob?.cancel()
        singBoxRecoveryJob = null
        prefs.edit().putBoolean("vpn_requested", false).apply()
        handoverJob?.cancel()
        verificationSession = null
        verificationTarget = null
        verificationJob?.cancel()
        ActiveVpnSubscriptionProxy.clear()
        running = false
        prefs.edit().putBoolean("vpn_running", false)
            .putLong("vpn_connected_since_elapsed", 0L).apply()
        setState(STATUS_DISCONNECTED)
        super.onRevoke()
    }

    override fun onDestroy() {
        singBoxRecoveryJob?.cancel()
        singBoxRecoveryJob = null
        verificationSession = null
        verificationTarget = null
        verificationJob?.cancel()
        VpnRuntimeBridge.publish(STATUS_DISCONNECTED)
        ActiveVpnSubscriptionProxy.clear()
        if (::prefs.isInitialized) {
            running = false
            prefs.edit().putBoolean("vpn_running", false)
                .putString("vpn_status", STATUS_DISCONNECTED)
                .putLong("vpn_connected_since_elapsed", 0L).apply()
            PhantomQuickTileService.notifyStateChanged(applicationContext)
            sendStatus(STATUS_DISCONNECTED)
        }
        handoverJob?.cancel()
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(callback)
            }
        }
        trafficJob?.cancel()
        releaseWakeLock()
        // onDestroy runs on Android main thread. Retire worker in an independent
        // cleanup thread so no synchronous IPC wait can stall the UI.
        singBoxSession = null
        val oldWorker = singBoxBridge
        singBoxBridge = null
        if (oldWorker != null) {
            Thread({ runCatching { oldWorker.stop() } }, "Phantom-SingBox-Cleanup").start()
        }
        runCatching { controller?.stopLoop() }
        runCatching { vpnInterface?.close() }
        ioScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_START = "xyz.wraithnet.phantom.START_VPN"
        const val ACTION_BOOT_START = "xyz.wraithnet.phantom.BOOT_START_VPN"
        const val ACTION_STOP = "xyz.wraithnet.phantom.STOP_VPN"
        const val ACTION_QUERY = "xyz.wraithnet.phantom.QUERY_VPN"
        const val ACTION_KEEP_ALIVE = "xyz.wraithnet.phantom.KEEP_ALIVE"
        const val ACTION_RELOAD = "xyz.wraithnet.phantom.RELOAD_VPN"
        const val ACTION_STATUS = "xyz.wraithnet.phantom.VPN_STATUS"
        const val ACTION_TRAFFIC = "xyz.wraithnet.phantom.VPN_TRAFFIC"
        const val ACTION_TRAFFIC_ON = "xyz.wraithnet.phantom.TRAFFIC_ON"
        const val ACTION_TRAFFIC_OFF = "xyz.wraithnet.phantom.TRAFFIC_OFF"
        const val EXTRA_DOWNLOAD_BYTES = "session_download_bytes"
        const val EXTRA_UPLOAD_BYTES = "session_upload_bytes"
        const val EXTRA_DOWNLOAD_BPS = "download_bytes_per_second"
        const val EXTRA_UPLOAD_BPS = "upload_bytes_per_second"
        const val EXTRA_CONNECTED_SINCE = "connected_since_elapsed"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DEGRADED = "degraded"
        fun isTunnelActive(status: String): Boolean = VpnTunnelHealth.isActive(status)
        const val STATUS_STARTING = "starting"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_ERROR = "error"
        private const val CHANNEL_ID = "phantom_vpn"
        private const val NOTIFICATION_ID = 1001
    }
}
