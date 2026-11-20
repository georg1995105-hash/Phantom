package xyz.wraithnet.phantom.ui.home

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionFetcher
import xyz.wraithnet.phantom.data.LatencyProbe
import xyz.wraithnet.phantom.data.ProtocolSupport
import xyz.wraithnet.phantom.data.ProfileSelection
import xyz.wraithnet.phantom.data.PhantomUpdates
import xyz.wraithnet.phantom.data.PhantomRelease
import xyz.wraithnet.phantom.data.CountryFlags
import xyz.wraithnet.phantom.data.FragmentationOptions
import java.io.File
import xyz.wraithnet.phantom.ui.settings.ComponentsScreen
import xyz.wraithnet.phantom.ui.settings.BackupScreen
import xyz.wraithnet.phantom.ui.settings.AppRoutingScreen
import xyz.wraithnet.phantom.ui.settings.DnsSettingsScreen
import xyz.wraithnet.phantom.service.VpnBootPolicy
import xyz.wraithnet.phantom.ui.settings.DiagnosticsScreen
import xyz.wraithnet.phantom.ui.settings.ThemeGalleryScreen
import xyz.wraithnet.phantom.service.PhantomQuickTileService
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.SubscriptionMutations
import xyz.wraithnet.phantom.data.SubscriptionQuickImport
import xyz.wraithnet.phantom.data.SubscriptionStore
import xyz.wraithnet.phantom.service.PhantomVpnService
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    refreshVersion: Int = 0,
    externalImport: String? = null,
    onExternalImportConsumed: () -> Unit = {},
    vpnStatus: String = "disconnected",
    vpnError: String = "",
    downloadBytes: Long = -1L,
    uploadBytes: Long = -1L,
    connectedSinceElapsed: Long = 0L,
    latencyMs: Long = -1L,
    latencyProfileHash: Int = Int.MIN_VALUE,
    latencyVersion: Int = 0,
    onStartVpn: (String) -> Unit = {},
    onStopVpn: () -> Unit = {},
    onThemeChange: (PhantomThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { SubscriptionStore(context) }
    val prefs = remember { context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE) }
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    fun softTap() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    LaunchedEffect(snackbar.currentSnackbarData) {
        if (snackbar.currentSnackbarData != null) {
            delay(1650L)
            snackbar.currentSnackbarData?.dismiss()
        }
    }
    val scope = rememberCoroutineScope()
    val subscriptionChanges = remember { Mutex() }
    var subscriptions by remember { mutableStateOf(store.read()) }
    var selectedId by remember { mutableStateOf(prefs.getString("selected_server_id", null)) }
    var selectedSubId by remember { mutableStateOf(prefs.getString("selected_subscription_id", null)) }
    var pinnedSubId by remember { mutableStateOf(prefs.getString("pinned_sub_id", null)) }
    val selectedMatch = ProfileSelection.resolve(subscriptions, selectedSubId, selectedId, pinnedSubId)
    val selected = selectedMatch?.second
    var refreshing by remember { mutableStateOf(setOf<String>()) }
    var probing by remember { mutableStateOf(emptySet<String>()) }
    val measuring = remember { mutableStateMapOf<String, String>() }
    val scanned = remember { mutableMapOf<String, List<ServerProfile>>() }
    val latencies = remember { mutableStateMapOf<String, LatencyProbe.PingResult>() }
    var showImportMenu by remember { mutableStateOf(false) }
    var activeSubMenu by remember { mutableStateOf<String?>(null) }
    var importMode by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(Destination.HOME) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorite_servers", emptySet()).orEmpty().toSet()) }
    var refreshFailed by remember { mutableStateOf(emptySet<String>()) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val lastAttempt = remember { mutableMapOf<String, Long>() }
    var connectedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vpnStatus, connectedSinceElapsed) {
        if (vpnStatus != "connected" || connectedSinceElapsed <= 0L) {
            connectedSeconds = 0L
        } else {
            while (true) {
                connectedSeconds =
                    ((SystemClock.elapsedRealtime() - connectedSinceElapsed).coerceAtLeast(0L)) / 1000L
                delay(1000L)
            }
        }
    }
    fun pingKey(owner: Subscription, server: ServerProfile): String = "${owner.id}:${server.id}"
    fun profileRaws(list: List<Subscription>) = list.flatMap { owner ->
        owner.servers.map { server -> pingKey(owner, server) to server.raw }
    }.toMap()
    val currentProfileRaws by remember { derivedStateOf { profileRaws(subscriptions) } }

    /** Do not carry an old RTT over to a new endpoint with the same JSON remark/id. */
    fun applySubscriptions(list: List<Subscription>) {
        val previous = currentProfileRaws
        val incoming = profileRaws(list)
        latencies.keys.toList().filter { key ->
            incoming[key] == null || previous[key] != incoming[key]
        }.forEach { latencies.remove(it) }
        scanned.keys.retainAll(list.map { it.id }.toSet())
        subscriptions = list
    }

    suspend fun persist(change: (List<Subscription>) -> List<Subscription>) {
        // Large lists (5,000 nodes) must never be serialized/committed on
        // Android's UI thread. Only the Compose state is applied on Main.
        subscriptionChanges.withLock {
            val updated = withContext(Dispatchers.IO) { store.update(change) }
            applySubscriptions(updated)
        }
    }

    suspend fun reloadSubscriptions() {
        subscriptionChanges.withLock {
            val stored = withContext(Dispatchers.IO) { store.read() }
            if (stored != subscriptions) applySubscriptions(stored)
        }
    }

    DisposableEffect(store) {
        val unsubscribe = store.observeChanges { scope.launch { reloadSubscriptions() } }
        onDispose { unsubscribe() }
    }
    // Cached VPN RTT belongs only to the actually running profile.
    LaunchedEffect(vpnStatus, connectedSinceElapsed, selectedSubId, selectedId, subscriptions) {
        val current = selectedMatch?.takeIf {
            vpnStatus == "connected" &&
                prefs.getString("vpn_last_profile", null) == it.second.raw
        }
        val currentKey = current?.let { (owner, server) -> pingKey(owner, server) }
        latencies.keys.toList().filter {
            latencies[it]?.source == "VPN" && it != currentKey
        }.forEach { latencies.remove(it) }
        current?.let { (owner, server) ->
            val rtt = LatencyProbe.lastVerifiedTunnelLatency(context, server)
            if (rtt > 0L) latencies[pingKey(owner, server)] =
                LatencyProbe.PingResult(rtt, "VPN")
        }
    }

    // Live on-demand HTTPS result from the existing Xray core.
    LaunchedEffect(latencyVersion) {
        if (latencyVersion == 0) return@LaunchedEffect
        selectedMatch?.takeIf {
            vpnStatus == "connected" &&
                it.second.raw.hashCode() == latencyProfileHash &&
                prefs.getString("vpn_last_profile", null) == it.second.raw
        }?.let { (owner, server) ->
            val key = pingKey(owner, server)
            if (latencyMs > 0L) latencies[key] = LatencyProbe.PingResult(latencyMs, "VPN")
            else latencies.remove(key)
        }
    }

    fun copyLink(value: String, message: String) {
        if (value.isBlank()) {
            scope.launch { snackbar.showSnackbar("Нет ссылки для копирования") }
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Android 13+: suppress plaintext previews of VPN secrets in the system clipboard.
        val clip = ClipData.newPlainText("Phantom VPN", value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        softTap()
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun pasteText(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    fun add(raw: String, customTitle: String? = null) {
        if (importBusy) return
        if (raw.isBlank()) {
            scope.launch { snackbar.showSnackbar("Вставьте ссылку подписки") }
            return
        }
        val source = raw.trim()
        if (subscriptions.any { it.source.trim() == source }) {
            val warning = "Эта подписка уже добавлена"
            if (importMode != null) importError = warning
            else scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(warning)
            }
            return
        }
        importBusy = true
        importError = null
        scope.launch {
            val message = try {
                val remote = source.startsWith("https://") || source.startsWith("http://")
                val downloaded = if (remote) SubscriptionFetcher.download(context, source, store.deviceId()) else null
                val parsed = SubscriptionParser.parse(downloaded?.body ?: source, customTitle ?: downloaded?.title)
                val subscription = parsed.copy(
                    source = if (remote) source else parsed.source,
                    customTitle = customTitle?.takeIf { it.isNotBlank() },
                    updateIntervalSeconds = downloaded?.updateIntervalSeconds ?: 3600L,
                    uploadBytes = downloaded?.uploadBytes,
                    downloadBytes = downloaded?.downloadBytes,
                    totalBytes = downloaded?.totalBytes,
                    expiresAt = downloaded?.expiresAt
                )
                persist { current ->
                    require(current.none { it.source.trim() == source }) {
                        "Эта подписка уже добавлена"
                    }
                    current + subscription
                }
                if (ProfileSelection.resolve(subscriptions.filterNot { it.id == subscription.id },
                        selectedSubId, selectedId, pinnedSubId) == null) {
                    selectedId = subscription.servers.firstOrNull()?.id
                    selectedSubId = subscription.id
                    prefs.edit().putString("selected_server_id", selectedId)
                        .putString("selected_subscription_id", selectedSubId).apply()
                }
                importMode = null
                showImportMenu = false
                softTap()
                // Import has finished before the success snackbar is shown.
                // A second tap must be able to report a duplicate immediately.
                "Подписка добавлена"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e.message ?: "Не удалось добавить подписку"
                if (importMode != null) {
                    importError = failure
                    null
                } else failure
            } finally {
                importBusy = false
            }
            message?.let {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(it)
            }
        }
    }

    // Quick import is intentionally a single action; no second paste or confirmation.
    // Only URLs/share links are auto-opened from the clipboard. Arbitrary clipboard
    // text must never trigger a remote request or masquerade as a subscription.
    fun quickImportFromClipboard() {
        if (importBusy) return
        val copied = runCatching { pasteText() }.getOrDefault("")
        if (SubscriptionQuickImport.isSubscriptionLink(copied)) {
            add(copied)
        } else {
            importError = null
            importMode = "url"
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    fun scanQr() {
        scanLauncher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(xyz.wraithnet.phantom.ui.PhantomCaptureActivity::class.java)
                .setPrompt("Наведите камеру на QR-код подписки")
                .setOrientationLocked(true)
                .setBeepEnabled(false)
        )
    }

    LaunchedEffect(externalImport) {
        externalImport?.takeIf { it.isNotBlank() }?.let {
            add(it)
            onExternalImportConsumed()
        }
    }

    suspend fun refresh(item: Subscription, quiet: Boolean = false) {
        if (!item.source.startsWith("http")) {
            if (!quiet) snackbar.showSnackbar("Для обновления нужна URL-ссылка подписки")
            return
        }
        if (item.id in refreshing) return
        lastAttempt[item.id] = System.currentTimeMillis()
        refreshing = refreshing + item.id
        val message = try {
            val remote = SubscriptionFetcher.download(context, item.source, store.deviceId())
            val parsed = SubscriptionParser.parse(remote.body,
                item.customTitle ?: remote.title ?: item.title)
            val updated = parsed.copy(
                id = item.id,
                source = item.source,
                updatedAt = System.currentTimeMillis(),
                title = item.customTitle ?: remote.title ?: item.title,
                updateIntervalSeconds = item.customIntervalSeconds ?: remote.updateIntervalSeconds ?: item.updateIntervalSeconds,
                customTitle = item.customTitle,
                customIntervalSeconds = item.customIntervalSeconds,
                uploadBytes = remote.uploadBytes ?: item.uploadBytes,
                downloadBytes = remote.downloadBytes ?: item.downloadBytes,
                totalBytes = remote.totalBytes ?: item.totalBytes,
                expiresAt = remote.expiresAt ?: item.expiresAt
            )
            persist { current ->
                // Fail the manual action if its owner was deleted or its URL
                // changed while downloading. Never report a false success.
                SubscriptionMutations.replaceDownloaded(
                    current, item, updated, strict = true
                )
            }
            refreshFailed = refreshFailed - item.id
            if (!quiet) softTap()
            "Подписка обновлена"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            refreshFailed = refreshFailed + item.id
            "Не удалось обновить подписку. Сохранённые серверы доступны."
        } finally {
            refreshing = refreshing - item.id
        }
        if (!quiet) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    val pingParallelism = LatencyProbe.MAX_PARALLEL_PROBES
    val pingLimit = remember { Semaphore(pingParallelism) }
    fun probeSubscription(item: Subscription, notify: Boolean = true) {
        if (item.id in probing || (!notify && scanned[item.id] == item.servers)) return
        val connected = selectedMatch?.takeIf {
            it.first.id == item.id && vpnStatus == "connected" &&
                prefs.getString("vpn_last_profile", null) == it.second.raw
        }
        probing = probing + item.id
        scanned[item.id] = item.servers
        scope.launch {
            val message = try {
                if (notify && connected != null) {
                    runCatching {
                        context.startService(Intent(context, PhantomVpnService::class.java)
                            .setAction(PhantomVpnService.ACTION_MEASURE_LATENCY)
                            .putExtra(PhantomVpnService.EXTRA_PROFILE, connected.second.raw))
                    }
                }
                val servers = item.servers.filter { ProtocolSupport.isSupported(it.scheme) }
                val probeOrder = withContext(Dispatchers.Default) {
                    // Get quick handshakes onto the screen while slow UDP peers are pending.
                    val (udp, tcp) = servers.partition(LatencyProbe::needsTunnel)
                    (tcp + udp).filter { it.id != connected?.second?.id }
                }
                coroutineScope {
                    val pending = probeOrder.iterator()
                    repeat(minOf(pingParallelism, probeOrder.size)) {
                        launch {
                            // The workers share this iterator on Main and yield at each probe.
                            while (pending.hasNext()) {
                                val server = pending.next()
                                val key = pingKey(item, server)
                                pingLimit.withPermit {
                                    if (currentProfileRaws[key] != server.raw) return@withPermit
                                    measuring[key] = server.raw
                                    try {
                                        val result = LatencyProbe.measureResult(context.applicationContext, server)
                                        if (currentProfileRaws[key] == server.raw && latencies[key]?.source != "VPN") {
                                            if (result != null) latencies[key] = result else latencies.remove(key)
                                        }
                                    } finally {
                                        measuring.remove(key)
                                    }
                                }
                            }
                        }
                    }
                }
                when {
                    !notify -> ""
                    connected != null -> "Задержка обновится после проверки"
                    servers.any { latencies[pingKey(item, it)] != null } -> "Проверка задержки завершена"
                    withContext(Dispatchers.Default) { servers.any(LatencyProbe::needsTunnel) } ->
                        "Для проверки задержки подключитесь к серверу"
                    else -> "Не получено ни одного результата проверки"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Не удалось проверить задержку"
            } finally {
                probing = probing - item.id
            }
            if (notify) snackbar.showSnackbar(message)
        }
    }

    LaunchedEffect(refreshVersion) {
        reloadSubscriptions()
    }

    // Refresh stale subscriptions while the UI exists; manual refresh is always available.
    LaunchedEffect(Unit) {
        delay(3000)
        while (true) {
            val now = System.currentTimeMillis()
            subscriptions.filter { item ->
                item.source.startsWith("http") &&
                    now - item.updatedAt >= item.updateIntervalSeconds.coerceAtLeast(900) * 1000L &&
                    now - (lastAttempt[item.id] ?: 0L) >= 15 * 60_000L &&
                    item.id !in refreshing
            }.forEach { refresh(it, quiet = true) }
            delay(60_000)
        }
    }

    var availableUpdate by remember { mutableStateOf<PhantomRelease?>(null) }
    var otaChecking by remember { mutableStateOf(false) }
    var otaDownload by remember { mutableStateOf(false) }
    var otaProgress by remember { mutableIntStateOf(0) }
    var otaFile by remember { mutableStateOf<File?>(null) }
    var otaError by remember { mutableStateOf<String?>(null) }
    var otaDismissed by remember { mutableStateOf(false) }
    var showOtaSheet by remember { mutableStateOf(false) }
    // Only an explicit check from Settings may open the update sheet over Settings.
    var otaManuallyRequested by remember { mutableStateOf(false) }
    var lastOtaCheck by remember { mutableLongStateOf(0L) }
    var lastOtaAttempt by remember { mutableLongStateOf(0L) }

    // Retry after network failures on a later foreground, instead of suppressing OTA for six hours.
    LaunchedEffect(refreshVersion) {
        val now = System.currentTimeMillis()
        if (now - lastOtaCheck >= 6L * 60 * 60 * 1000L &&
            now - lastOtaAttempt >= 2L * 60 * 1000L) {
            lastOtaAttempt = now
            try {
                val result = PhantomUpdates.check(context)
                lastOtaCheck = System.currentTimeMillis()
                if (result?.version != availableUpdate?.version) {
                    otaFile = null
                    otaDismissed = false
                }
                availableUpdate = result
                if (result != null && !otaDismissed) {
                    otaManuallyRequested = false
                    showOtaSheet = true
                }
                otaError = null
            } catch (_: Exception) {
                // Existing VPN functionality must not depend on the update service.
            }
        }
    }

    fun checkAppUpdateNow() {
        if (otaChecking || otaDownload) return
        otaChecking = true
        scope.launch {
            try {
                val result = PhantomUpdates.check(context)
                lastOtaCheck = System.currentTimeMillis()
                lastOtaAttempt = lastOtaCheck
                if (result?.version != availableUpdate?.version) otaFile = null
                availableUpdate = result
                otaError = null
                if (result != null) {
                    otaDismissed = false
                    otaManuallyRequested = true
                    showOtaSheet = true
                } else {
                    showOtaSheet = false
                    snackbar.showSnackbar("Установлена актуальная версия")
                }
            } catch (_: Exception) {
                snackbar.showSnackbar("Не удалось проверить обновления. Проверьте соединение.")
            } finally {
                otaChecking = false
            }
        }
    }

    fun startOtaInstall() {
        if (otaDownload) return
        val release = availableUpdate ?: return
        otaError = null
        scope.launch {
            try {
                val file = otaFile?.takeIf { it.exists() } ?: run {
                    otaDownload = true
                    otaProgress = 0
                    PhantomUpdates.download(context, release) { otaProgress = it }
                        .also { otaFile = it }
                }
                val launched = PhantomUpdates.openInstaller(context, file)
                if (!launched) {
                    otaError = "Разрешите установку из Phantom и нажмите «Установить» ещё раз"
                }
            } catch (e: Exception) {
                otaError = e.message ?: "Не удалось установить обновление"
            } finally {
                otaDownload = false
            }
        }
    }

    BackHandler(enabled = tab != Destination.HOME) { tab = Destination.HOME }
    var showErrorDetails by remember { mutableStateOf(false) }
    val tv = isTelevision()
    Scaffold(
        modifier = if (tv) Modifier.padding(horizontal = 32.dp, vertical = 14.dp) else Modifier,
        topBar = {
            Box(Modifier.statusBarsPadding()) { PageTitle(tab) }
        },
        bottomBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (tab == Destination.HOME) {
                    ConnectionImportActions(
                        onQuickImport = { if (tv) showImportMenu = true else quickImportFromClipboard() },
                        onScanQr = if (tv || !context.packageManager.hasSystemFeature(
                                android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) null else ::scanQr,
                        hasSubscriptions = subscriptions.isNotEmpty(),
                        importing = importBusy,
                    )
                }
                TabBar(tab) { tab = it }
            }
        }
    ) { inner ->
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)
            .background(Brush.linearGradient(listOf(colors.background,
                colors.surface, colors.background)))) {
            when (tab) {
                Destination.HOME -> ConnectionPage(vpnStatus, selected, downloadBytes, uploadBytes, connectedSeconds,
                    toggle = {
                        softTap()
                        when {
                            vpnStatus == "connected" -> onStopVpn()
                            selected == null -> { tab = Destination.SUBSCRIPTIONS; showImportMenu = true }
                            else -> onStartVpn(selected.raw)
                        }
                    }, choose = { tab = Destination.SERVERS },
                    error = if (vpnStatus == "error" && vpnError.isNotBlank()) ({ showErrorDetails = true }) else null,
                    onRefreshSubscription = {
                        selectedMatch?.first?.let { owner ->
                            scope.launch { refresh(owner) }
                        }
                    },
                    subscriptionRefreshing = selectedMatch?.first?.id?.let { it in refreshing } == true,
                    subscriptionAvailable = selectedMatch != null,
                    hasSubscriptions = subscriptions.isNotEmpty())
                Destination.SERVERS -> ServersPage(subscriptions, selectedMatch, pinnedSubId, favorites, latencies,
                    probing = probing, measuring = measuring, refreshing = refreshing, failed = refreshFailed,
                    onRefresh = { sub -> scope.launch { refresh(sub) } },
                    onProbe = { sub -> probeSubscription(sub) },
                    onAutoProbe = { sub -> probeSubscription(sub, notify = false) },
                    favorite = { id ->
                        favorites = if (id in favorites) favorites - id else favorites + id
                        prefs.edit().putStringSet("favorite_servers", favorites).apply()
                    }, choose = { owner, server ->
                        if (ProtocolSupport.isSupported(server.scheme)) {
                            val changed = selectedMatch?.first?.id != owner.id || selected?.id != server.id ||
                                vpnStatus == "connected" && prefs.getString("vpn_last_profile", null) != server.raw
                            softTap()
                            selectedId = server.id
                            selectedSubId = owner.id
                            prefs.edit().putString("selected_server_id", server.id)
                                .putString("selected_subscription_id", owner.id).apply()
                            scope.launch {
                                persist { current ->
                                    current.map { item ->
                                        if (item.id == owner.id && item.source == owner.source &&
                                            item.servers.any { it.id == server.id })
                                            item.copy(selectedServerId = server.id)
                                        else item
                                    }
                                }
                            }
                            if (changed && vpnStatus in setOf("connected", "starting")) onStartVpn(server.raw)
                        } else scope.launch { snackbar.showSnackbar("Этот протокол пока не поддерживается") }
                    }, add = { importError = null; importMode = "manual" })
                Destination.SUBSCRIPTIONS -> SubscriptionPage(subscriptions.sortedByDescending { it.id == pinnedSubId },
                    selectedMatch?.first?.id, refreshing, refreshFailed,
                    onRefresh = { sub -> scope.launch { refresh(sub) } },
                    manage = { activeSubMenu = it.id }, add = { showImportMenu = true },
                    shop = { renew ->
                        val url = if (renew) "https://go.veor.pro/" else "https://go.veor.pro/#plans"
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                        try {
                            context.startActivity(intent)
                        } catch (_: android.content.ActivityNotFoundException) {
                            scope.launch { snackbar.showSnackbar("Установите браузер для открытия магазина") }
                        }
                    })
                Destination.SETTINGS -> PhantomSettings(
                    onThemeChange = onThemeChange,
                    onCheckAppUpdate = ::checkAppUpdateNow,
                    onBackupRestored = {
                        applySubscriptions(store.read())
                        selectedId = prefs.getString("selected_server_id", null)
                        selectedSubId = prefs.getString("selected_subscription_id", null)
                        pinnedSubId = prefs.getString("pinned_sub_id", null)
                        favorites = prefs.getStringSet("favorite_servers", emptySet()).orEmpty().toSet()
                        runCatching { PhantomThemeMode.valueOf(prefs.getString("theme", "FOREST") ?: "FOREST") }
                            .getOrNull()?.let(onThemeChange)
                        tab = Destination.HOME
                        scope.launch { snackbar.showSnackbar("Резервная копия восстановлена") }
                    })
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().padding(horizontal = 30.dp, vertical = 14.dp),
                snackbar = { data ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = .4f)),
                        shadowElevation = 3.dp
                    ) {
                        Text(data.visuals.message,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            )
        }
    }
    if (showOtaSheet && availableUpdate != null &&
        (tab != Destination.SETTINGS || otaManuallyRequested) &&
        !showImportMenu && activeSubMenu == null && importMode == null) {
        AppUpdateSheet(
            release = availableUpdate!!,
            downloading = otaDownload,
            progress = otaProgress,
            readyToInstall = otaFile?.exists() == true,
            error = otaError,
            onLater = { showOtaSheet = false; otaDismissed = true },
            onUpdate = { softTap(); startOtaInstall() },
        )
    }

    if (showImportMenu) {
        ModalBottomSheet(
            onDismissRequest = { showImportMenu = false },
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text("Добавить конфигурацию",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                funActionRow("Ссылка подписки", Icons.Default.Link) {
                    showImportMenu = false; importMode = "url"
                }
                funActionRow("Из буфера обмена", Icons.Default.ContentPaste) {
                    showImportMenu = false; quickImportFromClipboard()
                }
                if (!tv || context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) funActionRow("Сканировать QR", Icons.Default.QrCodeScanner) {
                    showImportMenu = false
                    scanQr()
                }
                funActionRow("Ручной ввод", Icons.Default.Edit) {
                    showImportMenu = false; importMode = "manual"
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showErrorDetails && vpnError.isNotBlank()) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { showErrorDetails = false },
            title = { Text("Ошибка подключения") },
            text = { Text(vpnError, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { showErrorDetails = false }) { Text("Закрыть") } }
        )
    }

    activeSubMenu?.let { id ->
        val sub = subscriptions.firstOrNull { it.id == id }
        if (sub != null) {
            ModalBottomSheet(
                onDismissRequest = { activeSubMenu = null },
                containerColor = MaterialTheme.colorScheme.surface,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)) {
                    Text(sub.title, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    funActionRow(
                        if (pinnedSubId == sub.id) "Открепить" else "Закрепить",
                        Icons.Default.PushPin
                    ) {
                        pinnedSubId = if (pinnedSubId == sub.id) null else sub.id
                        prefs.edit().putString("pinned_sub_id", pinnedSubId).apply()
                        activeSubMenu = null
                        scope.launch {
                            snackbar.showSnackbar(if (pinnedSubId == sub.id)
                                "Закреплено сверху" else "Откреплено")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                    val isRemote = sub.source.startsWith("https://", ignoreCase = true) ||
                        sub.source.startsWith("http://", ignoreCase = true)
                    funActionRow(when {
                        isRemote -> "Копировать подписку"
                        sub.servers.size == 1 -> "Копировать ключ"
                        else -> "Копировать конфигурацию"
                    }, Icons.Default.ContentCopy) {
                        activeSubMenu = null
                        copyLink(sub.source, when {
                            isRemote -> "Ссылка подписки скопирована"
                            sub.servers.size == 1 -> "Ключ скопирован"
                            else -> "Конфигурация скопирована"
                        })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                    funActionRow(when {
                        isRemote -> "Удалить подписку"
                        sub.servers.size == 1 -> "Удалить ключ"
                        else -> "Удалить конфигурацию"
                    }, Icons.Default.DeleteOutline) {
                        activeSubMenu = null
                        deleteId = sub.id
                    }
                }
            }
        }
    }

    if (importMode != null) {
        val manual = importMode == "manual"
        var value by remember(importMode) { mutableStateOf("") }
        var optionalName by remember(importMode) { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { if (!importBusy) importMode = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                    .padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(if (manual) "Ручной ввод" else "Добавить подписку",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (manual) "VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS и Xray JSON."
                    else "Вставьте URL подписки любого совместимого сервиса.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; importError = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = !manual,
                    minLines = if (manual) 5 else 1,
                    maxLines = if (manual) 10 else 1,
                    label = { Text(if (manual) "Ссылка или конфигурация" else "Ссылка подписки") },
                    placeholder = { Text(if (manual) "vless://…   или JSON"
                        else "https://…") },
                    keyboardOptions = KeyboardOptions(keyboardType =
                        if (manual) KeyboardType.Text else KeyboardType.Uri)
                )
                importError?.let { error ->
                    Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                if (manual) {
                    OutlinedTextField(
                        value = optionalName,
                        onValueChange = { optionalName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Название (необязательно)") }
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = { value = pasteText() },
                        enabled = !importBusy
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Вставить")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { add(value, optionalName.takeIf { it.isNotBlank() }) },
                        enabled = value.isNotBlank() && !importBusy,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (importBusy) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (importBusy) "Добавление…" else "Добавить")
                    }
                }
            }
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { deleteId = null },
            title = { Text(if (subscriptions.firstOrNull { it.id == id }
                    ?.servers?.size == 1 && subscriptions.firstOrNull { it.id == id }
                    ?.source?.startsWith("http") == false) "Удалить ключ?"
                    else "Удалить подписку?") },
            text = { Text("Конфигурация будет удалена с устройства.") },
            confirmButton = { TextButton(onClick = {
                if (selectedMatch?.first?.id == id && vpnStatus == "connected") onStopVpn()
                scope.launch {
                    try {
                        persist { current -> current.filterNot { sub -> sub.id == id } }
                        favorites = favorites.filterNot { it.startsWith("$id:") }.toSet()
                        prefs.edit().putStringSet("favorite_servers", favorites).apply()
                    } catch (_: Exception) {
                        snackbar.showSnackbar("Не удалось сохранить изменения. Подписка не удалена.")
                    } finally {
                        deleteId = null
                    }
                }
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } }
        )
    }
}

internal fun displayServerName(server: ServerProfile): String =
    server.name.trim().removePrefix(server.countryFlag)
        .removePrefix("🌐").removePrefix("📶").trim().ifBlank { server.name }


@Composable
private fun funActionRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 55.dp)
        .tvFocusRing().clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

internal fun protocolLabel(server: ServerProfile): String {
    if (server.scheme.lowercase() !in setOf("xray", "json")) return when (server.scheme.lowercase()) {
        "hysteria", "hysteria2", "hy2" -> "Hysteria2"
        else -> server.scheme.uppercase()
    }
    return runCatching {
        val root = JSONObject(server.raw)
        val array = root.optJSONArray("outbounds")
        val proxy = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
            .firstOrNull { it.optString("protocol") !in setOf("freedom", "blackhole", "dns") }
            ?: root
        val protocol = proxy.optString("protocol").ifBlank { "Xray" }
        val stream = proxy.optJSONObject("streamSettings")
        val transport = stream?.optString("method")?.ifBlank { stream.optString("network") }
        val security = stream?.optString("security")
        listOf(protocol, transport, security)
            .filterNot { it.isNullOrBlank() || it.equals("none", true) || it.equals("raw", true) }
            .map { it!!.uppercase(Locale.ROOT) }
            .distinct().joinToString(" · ").ifBlank { "Xray" }
    }.getOrDefault("Xray")
}

internal fun sizeLabel(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[i])
}

internal fun timeLabel(millis: Long): String =
    SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault()).format(Date(millis))

internal fun dateLabel(seconds: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(seconds * 1000L))

@Composable
private fun PhantomSettings(
    onThemeChange: (PhantomThemeMode) -> Unit,
    onCheckAppUpdate: () -> Unit,
    onBackupRestored: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(runCatching {
            PhantomThemeMode.valueOf(prefs.getString("theme", "FOREST") ?: "FOREST")
        }.getOrDefault(PhantomThemeMode.FOREST))
    }
    var keepActive by remember { mutableStateOf(prefs.getBoolean("keep_active", false)) }
    var bootConnect by remember { mutableStateOf(prefs.getBoolean(VpnBootPolicy.PREF_KEY, false)) }

    var showThemeGallery by remember { mutableStateOf(false) }
    var tileMessage by remember { mutableStateOf<String?>(null) }
    var fragment by remember { mutableStateOf(FragmentationOptions.load(context)) }
    var fragmentMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showDns by remember { mutableStateOf(false) }

    var showComponents by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    if (showThemeGallery) {
        ThemeGalleryScreen(
            selected = mode,
            onChoose = { chosen -> mode = chosen; onThemeChange(chosen) },
            onBack = { showThemeGallery = false }
        )
        return
    }
    if (showLog) { DiagnosticsScreen(onBack = { showLog = false }); return }
    if (showApps) { AppRoutingScreen(onBack = { showApps = false }); return }
    if (showDns) { DnsSettingsScreen(onBack = { showDns = false }); return }

    if (showBackup) {
        BackupScreen(
            onBack = { showBackup = false },
            onRestored = {
                showBackup = false
                onBackupRestored()
            }
        )
        return
    }
    if (showComponents) {
        ComponentsScreen(onBack = { showComponents = false })
        return
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }

    fun openSystemSettings(action: String) {
        try {
            context.startActivity(Intent(action))
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
    var group by rememberSaveable { mutableStateOf("") }
    fun toggle(name: String) { group = if (group == name) "" else name }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Panel {
                SettingsAction("Подключение", "DNS, фрагментация TLS", Icons.Default.Tune) { toggle("network") }
                if (group == "network") {
                    funActionRow("DNS", Icons.Default.Dns) { showDns = true }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Фрагментация TLS", fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Для TCP-соединений с TLS/REALITY. На Hysteria2 не действует.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = fragment.enabled, onCheckedChange = { enabled ->
                            val next = fragment.copy(enabled = enabled)
                            if (next.isValid()) {
                                fragment = next
                                FragmentationOptions.save(context, next)
                                fragmentMessage = "При следующем подключении изменения вступят в силу"
                            } else {
                                fragmentMessage = "Исправьте значения фрагментации"
                            }
                        })
                    }
                    if (fragment.enabled) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = fragment.lengths,
                                onValueChange = { fragment = fragment.copy(lengths = it) },
                                label = { Text("Размер, байт") },
                                placeholder = { Text("80-120") },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = fragment.delays,
                                onValueChange = { fragment = fragment.copy(delays = it) },
                                label = { Text("Задержка, мс") },
                                placeholder = { Text("3-10") },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }
                        TextButton(
                            enabled = fragment.isValid(),
                            onClick = {
                                FragmentationOptions.save(context, fragment)
                                fragmentMessage = "Настройки сохранены. Переподключите VPN."
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) { Text("Применить параметры") }
                    }
                    fragmentMessage?.let {
                        Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Panel { SettingsAction("Тема оформления", mode.title, Icons.Default.Palette) { showThemeGallery = true } } }
        item {
            Panel {
                SettingsAction("Кнопка в шторке Android", "Быстрое подключение VPN", Icons.Default.PowerSettingsNew) {
                    PhantomQuickTileService.offerToAdd(context) { tileMessage = it }
                }
                tileMessage?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Panel {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            bootConnect = !bootConnect
                            prefs.edit().putBoolean(VpnBootPolicy.PREF_KEY, bootConnect).apply()
                        }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Подключаться после перезагрузки", fontSize = 15.sp,
                                fontWeight = FontWeight.Medium)
                            Text("Использовать последний сервер после включения телефона. " +
                                "Сначала разрешите VPN в Android.",
                                fontSize = 12.sp, lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = bootConnect, onCheckedChange = { checked ->
                            bootConnect = checked
                            prefs.edit().putBoolean(VpnBootPolicy.PREF_KEY, checked).apply()
                        })
                    }
            }
        }
        item { Panel { SettingsAction("Туннелирование", "Выбор приложений", Icons.Default.Layers) { showApps = true } } }
        item {
            Panel {
                SettingsAction("Фоновая работа", "Экран, батарея, всегда включённый VPN", Icons.Default.BatteryStd) { toggle("background") }
                if (group == "background") {
                    Row(Modifier.fillMaxWidth().clickable {
                        keepActive = !keepActive
                        prefs.edit().putBoolean("keep_active", keepActive).apply()
                        if (prefs.getBoolean("vpn_running", false)) {
                            context.startService(Intent(context, PhantomVpnService::class.java)
                                .setAction(PhantomVpnService.ACTION_KEEP_ALIVE))
                        }
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Работа при выключенном экране",
                                fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            Text("Удерживает процессор активным. Повышает расход батареи.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.width(7.dp))
                        Switch(checked = keepActive, onCheckedChange = { enabled ->
                            keepActive = enabled
                            prefs.edit().putBoolean("keep_active", enabled).apply()
                            if (prefs.getBoolean("vpn_running", false)) {
                                context.startService(Intent(context, PhantomVpnService::class.java)
                                    .setAction(PhantomVpnService.ACTION_KEEP_ALIVE))
                            }
                        })
                    }
                    funActionRow("Всегда включённый VPN", Icons.Default.Settings) {
                        openSystemSettings(Settings.ACTION_VPN_SETTINGS)
                    }
                    funActionRow("Ограничения батареи", Icons.Default.BatteryStd) {
                        openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    }
                }
            }
        }
        item { Panel { SettingsAction("Резервная копия", "Сохранение и восстановление", Icons.Default.Backup) { showBackup = true } } }
        item {
            Panel {
                SettingsAction("О приложении", "Phantom $version", Icons.Default.Info) { toggle("about") }
                if (group == "about") {
                    funActionRow("Проверить обновление приложения", Icons.Default.SystemUpdateAlt) {
                        onCheckAppUpdate()
                    }
                    funActionRow("Компоненты и лицензии", Icons.Default.Layers) { showComponents = true }
                    funActionRow("Журнал VPN", Icons.Default.List) { showLog = true }
                }
            }
        }
    }
}

@Composable
private fun SettingsAction(title: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: () -> Unit) {
    Row(Modifier.fillMaxWidth().tvFocusRing().clickable(onClick = action).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null)
    }
}
