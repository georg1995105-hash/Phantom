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
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.ActiveVpnSubscriptionProxy
import xyz.wraithnet.phantom.data.SubscriptionFetcher
import xyz.wraithnet.phantom.data.LatencyProbe
import xyz.wraithnet.phantom.data.VpnEventLog
import xyz.wraithnet.phantom.service.physicalNetwork
import android.net.ConnectivityManager
import xyz.wraithnet.phantom.service.PhantomAutoUpdateWorker
import xyz.wraithnet.phantom.data.ProtocolSupport
import xyz.wraithnet.phantom.data.ProfileSelection
import xyz.wraithnet.phantom.data.PhantomUpdates
import xyz.wraithnet.phantom.data.PhantomRelease
import xyz.wraithnet.phantom.data.CountryFlags
import xyz.wraithnet.phantom.data.ExitNetworkLookup
import xyz.wraithnet.phantom.data.FragmentationOptions
import java.io.File
import xyz.wraithnet.phantom.ui.settings.ComponentsScreen
import xyz.wraithnet.phantom.ui.settings.BackupScreen
import xyz.wraithnet.phantom.ui.settings.AppRoutingScreen
import xyz.wraithnet.phantom.ui.settings.DnsSettingsScreen
import xyz.wraithnet.phantom.service.VpnBootPolicy
import xyz.wraithnet.phantom.ui.settings.DiagnosticsScreen
import xyz.wraithnet.phantom.ui.settings.PingSettingsScreen
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import xyz.wraithnet.phantom.data.TvSubscriptionPairing


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
    onStartVpn: (String) -> Unit = {},
    onStopVpn: () -> Unit = {},
    onThemeChange: (PhantomThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { SubscriptionStore(context) }
    LaunchedEffect(Unit) { PhantomAutoUpdateWorker.schedule(context.applicationContext) }
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
    val scannedWithAutoTuic = remember { mutableMapOf<String, Boolean>() }
    val latencies = remember { mutableStateMapOf<String, Long>() }
    var showImportMenu by remember { mutableStateOf(false) }
    var showTvPairing by remember { mutableStateOf(false) }
    var tvPairCode by remember { mutableStateOf<String?>(null) }
    var tvPairError by remember { mutableStateOf<String?>(null) }
    var tvPairListener by remember { mutableStateOf<TvSubscriptionPairing.Listener?>(null) }
    var incomingPairCode by remember { mutableStateOf<String?>(null) }
    var pairingSending by remember { mutableStateOf(false) }
    var pairingSendError by remember { mutableStateOf<String?>(null) }
    val serverPageState = rememberSaveableStateHolder()
    var qrSource by remember { mutableStateOf<String?>(null) }
    var activeSubMenu by remember { mutableStateOf<String?>(null) }
    var importMode by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(Destination.HOME) }
    var exitNetwork by remember { mutableStateOf<ExitNetworkLookup.Info?>(null) }
    var exitLoading by remember { mutableStateOf(false) }
    var exitDetails by remember { mutableStateOf(false) }
    var checkedExitKey by remember { mutableStateOf<String?>(null) }
    var checkedExitAt by remember { mutableLongStateOf(0L) }
    // The app UID is outside Android TUN. Never request network identity without
    // the active authenticated proxy: it would show the mobile/Wi-Fi provider.
    LaunchedEffect(tab, vpnStatus, selected?.raw, connectedSinceElapsed) {
        val profile = selected?.raw
        if (vpnStatus != "connected" || profile.isNullOrBlank() || connectedSinceElapsed <= 0L) {
            exitNetwork = null
            exitLoading = false
            checkedExitKey = null
        } else if (tab == Destination.HOME) {
            val key = profile + ":" + connectedSinceElapsed
            val now = SystemClock.elapsedRealtime()
            if (key != checkedExitKey || now - checkedExitAt > 10 * 60_000L) {
                exitNetwork = null
                exitLoading = true
                checkedExitKey = key
                checkedExitAt = now
                try {
                    exitNetwork = ExitNetworkLookup.query(context, profile)
                } finally {
                    exitLoading = false
                }
            }
        }
    }
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
        }.forEach {
            latencies.remove(it)
        }
        scanned.keys.retainAll(list.map { it.id }.toSet())
        scannedWithAutoTuic.keys.retainAll(list.map { it.id }.toSet())
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

    fun add(raw: String, customTitle: String? = null, completed: ((Boolean) -> Unit)? = null) {
        if (importBusy) {
            completed?.invoke(false)
            return
        }
        if (raw.isBlank()) {
            scope.launch { snackbar.showSnackbar("Вставьте ссылку подписки") }
            completed?.invoke(false)
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
            completed?.invoke(false)
            return
        }
        importBusy = true
        importError = null
        scope.launch {
            var added = false
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
                    expiresAt = downloaded?.expiresAt,
                    announcement = downloaded?.announcement,
                    supportUrl = downloaded?.supportUrl,
                    webPageUrl = downloaded?.webPageUrl
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
                added = true
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
                completed?.invoke(added)
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

    val qrCameraAvailable = remember(context) { hasQrCamera(context) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { scanned ->
            if (TvSubscriptionPairing.isPairingCode(scanned)) {
                incomingPairCode = scanned
                pairingSendError = null
            } else add(scanned)
        }
    }
    fun scanQr() {
        if (!qrCameraAvailable) {
            importMode = "url"
            importError = "Камера недоступна. Введите ссылку подписки."
            return
        }
        runCatching {
            scanLauncher.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setCaptureActivity(xyz.wraithnet.phantom.ui.PhantomCaptureActivity::class.java)
                    .setPrompt("Наведите камеру на QR-код подписки")
                    .setOrientationLocked(true)
                    .setBeepEnabled(false)
            )
        }.onFailure {
            importMode = "url"
            importError = "Не удалось открыть камеру. Введите ссылку подписки."
        }
    }

    LaunchedEffect(externalImport) {
        externalImport?.takeIf { it.isNotBlank() }?.let { text ->
            if (TvSubscriptionPairing.isPairingCode(text)) {
                incomingPairCode = text
                pairingSendError = null
            } else add(text)
            onExternalImportConsumed()
        }
    }

    LaunchedEffect(showTvPairing) {
        if (!showTvPairing) return@LaunchedEffect
        tvPairCode = null
        tvPairError = null
        try {
            val listener = TvSubscriptionPairing.listen(context)
            tvPairListener = listener
            tvPairCode = listener.qrCode
            val saved = listener.receive { source, name ->
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        add(source, name) { success ->
                            if (continuation.isActive) continuation.resume(success)
                        }
                    }
                }
            }
            if (saved) {
                showTvPairing = false
            } else {
                tvPairError = "Не удалось сохранить подписку. Закройте окно и попробуйте снова."
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            tvPairError = if (error is java.net.SocketTimeoutException)
                "Время ожидания истекло. Откройте QR ещё раз."
                else error.message ?: "Не удалось принять подписку. Повторите попытку."
        } finally {
            tvPairListener?.close()
            tvPairListener = null
        }
    }

    DisposableEffect(showTvPairing) {
        onDispose { tvPairListener?.close() }
    }

    suspend fun refresh(item: Subscription, quiet: Boolean = false) {
        if (!item.source.startsWith("http")) {
            if (!quiet) snackbar.showSnackbar("Для обновления нужна URL-ссылка подписки")
            return
        }
        if (item.id in refreshing) return
        lastAttempt[item.id] = System.currentTimeMillis()
        refreshing = refreshing + item.id
        // The subscription can belong to ANY provider. The country being tested
        // is the selected, actually running VPN profile, not the subscription owner.
        val activeNode = selected
        val checkingNode = !quiet && vpnStatus == PhantomVpnService.STATUS_CONNECTED
        val message = try {
            if (!quiet && vpnStatus == PhantomVpnService.STATUS_STARTING) {
                error("Подключение VPN ещё не завершено")
            }
            val vpnRoute = if (checkingNode) {
                ActiveVpnSubscriptionProxy.forProfile(activeNode?.raw.orEmpty())
                    ?: error("Запущенная нода не соответствует выбранной")
            } else null
            // Never silently use the physical network when an active-node
            // refresh fails. Background refreshes intentionally stay direct.
            val remote = SubscriptionFetcher.download(
                context, item.source, store.deviceId(), item, vpnRoute = vpnRoute,
                manual = !quiet)
            val parsed = SubscriptionParser.parse(remote.body,
                item.customTitle ?: remote.title ?: item.title)
            if (vpnRoute != null && !ActiveVpnSubscriptionProxy.isActive(vpnRoute)) {
                error("VPN сменился во время обновления")
            }
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
                expiresAt = remote.expiresAt,
                announcement = remote.announcement,
                supportUrl = remote.supportUrl,
                webPageUrl = remote.webPageUrl
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
            when {
                !quiet && vpnStatus == PhantomVpnService.STATUS_STARTING ->
                    "Дождитесь подключения VPN и повторите проверку"
                else -> "Не удалось обновить подписку"
            }
        } finally {
            refreshing = refreshing - item.id
        }
        if (!quiet) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun probeSubscription(item: Subscription, notify: Boolean = true, tuicOnly: Boolean = false) {
        val autoTuic = LatencyProbe.autoTuicEnabled(context)
        if (item.id in probing || (!notify && scanned[item.id] == item.servers &&
                    scannedWithAutoTuic[item.id] == autoTuic)) return
        probing = probing + item.id
        // A manually requested TUIC probe must not suppress later automatic
        // TCP pings for another protocol in the same subscription.
        if (!tuicOnly) {
            scanned[item.id] = item.servers
            scannedWithAutoTuic[item.id] = autoTuic
        }
        scope.launch {
            val message = try {
                val probeOrder = item.servers.filter {
                    ProtocolSupport.isSupported(it.scheme) &&
                        (if (tuicOnly) ProtocolSupport.usesNativeLatency(it.scheme)
                         else (notify || ProtocolSupport.shouldAutoProbe(it.scheme, autoTuic)))
                }.sortedByDescending { item.id == selectedMatch?.first?.id && it.id == selected?.id }
                val remaining = probeOrder.iterator()
                // Keep individual failures local: a broken TUIC JNI/session
                // must not cancel healthy VLESS, HY2 or XHTTP pings.
                val outcomes = mutableListOf<Pair<String, Boolean>>()
                for (server in probeOrder) measuring[pingKey(item, server)] = server.raw
                coroutineScope {
                    repeat(minOf(8, probeOrder.size)) {
                        launch {
                            while (remaining.hasNext()) {
                                val server = remaining.next()
                                val key = pingKey(item, server)
                                val result = try {
                                    LatencyProbe.measureTcp(context.applicationContext, server)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    null
                                }
                                if (currentProfileRaws[key] == server.raw) {
                                    if (result == null) latencies.remove(key) else latencies[key] = result
                                    val label = when (server.scheme.lowercase(Locale.ROOT)) {
                                        "tuic" -> "TUIC"
                                        "hysteria2", "hy2", "hysteria" -> "Hysteria2"
                                        "anytls" -> "AnyTLS"
                                        "vless" -> "VLESS"
                                        "vmess" -> "VMess"
                                        "trojan" -> "Trojan"
                                        "ss", "shadowsocks" -> "Shadowsocks"
                                        else -> "Другой"
                                    }
                                    outcomes.add(label to (result != null))
                                }
                                measuring.remove(key)
                            }
                        }
                    }
                }
                // No server domains, profile names, URLs, keys, or payloads are
                // logged. This can be copied from Settings > VPN journal when
                // one phone returns dashes while another displays numbers.
                if (notify || outcomes.any { !it.second }) {
                    val summary = outcomes.groupBy { it.first }.entries
                        .sortedBy { it.key }
                        .joinToString(", ") { (name, samples) ->
                            "$name ${samples.count { it.second }}/${samples.size}"
                        }
                    val networkStatus = runCatching {
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        val network = manager.physicalNetwork()
                        val caps = network?.let(manager::getNetworkCapabilities)
                        val type = when {
                            network == null -> "нет физической сети"
                            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "мобильная"
                            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                            else -> "другая"
                        }
                        val verified = caps?.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                        "$type; Android проверил интернет: ${if (verified) "да" else "нет"}"
                    }.getOrDefault("информация о сети недоступна")
                    withContext(Dispatchers.IO) {
                        VpnEventLog.record(context.applicationContext, "ping",
                            "Результаты: ${outcomes.count { it.second }}/${outcomes.size}; " +
                                "сеть: $networkStatus; $summary")
                    }
                }
                when {
                    !notify -> ""
                    outcomes.any { it.second } -> "Проверка задержки завершена"
                    else -> "Не получено ни одного результата проверки. Подробности: Настройки → Журнал VPN"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Не удалось проверить задержку"
            } finally {
                item.servers.forEach { measuring.remove(pingKey(item, it)) }
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
    var otaDownloadJob by remember { mutableStateOf<Job?>(null) }
    var otaProgress by remember { mutableIntStateOf(0) }
    var otaSpeed by remember { mutableLongStateOf(0L) }
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
        if (otaDownload || otaDownloadJob?.isActive == true) return
        val release = availableUpdate ?: return
        otaError = null
        otaDownloadJob = scope.launch {
            try {
                val file = otaFile?.takeIf { it.exists() } ?: run {
                    otaDownload = true
                    otaProgress = 0
                    otaSpeed = 0L
                    PhantomUpdates.download(context, release,
                        onProgress = { otaProgress = it },
                        onSpeed = { otaSpeed = it })
                        .also { otaFile = it }
                }
                val launched = PhantomUpdates.openInstaller(context, file)
                if (!launched) {
                    otaError = "Разрешите установку из Phantom и нажмите «Установить» ещё раз"
                }
            } catch (_: CancellationException) {
                otaError = "Загрузка остановлена. Можно скачать через браузер."
            } catch (e: Exception) {
                otaError = e.message ?: "Не удалось установить обновление"
            } finally {
                otaDownload = false
                otaDownloadJob = null
            }
        }
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Установите браузер для открытия ссылки") }
        }
    }

    BackHandler(enabled = tab != Destination.HOME) { tab = Destination.HOME }
    var showErrorDetails by remember { mutableStateOf(false) }
    val tv = isTelevision()
    // On an empty television installation the useful QR is a receiver code:
    // scan it using Phantom on the phone and choose the subscription to send.
    // Do not auto-open the camera scanner (TV has no camera).
    LaunchedEffect(tv, subscriptions.isEmpty(), importBusy, externalImport) {
        // A deep link may be importing a profile during cold start; do not
        // overlay the pairing QR over it. Importing from the phone also updates
        // subscriptions BEFORE the encrypted receiver sends its ACK. Never
        // auto-close the QR on that update: only the receiver closes after ACK.
        if (tv && subscriptions.isEmpty() && !importBusy && externalImport.isNullOrBlank()) {
            showTvPairing = true
        }
    }
    Scaffold(
        modifier = if (tv) Modifier.padding(horizontal = 32.dp, vertical = 14.dp) else Modifier,
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                PageTitle(
                    tab,
                    exit = if (vpnStatus == "connected") exitNetwork else null,
                    checkingExit = vpnStatus == "connected" && exitLoading,
                    openExit = { exitDetails = true },
                )
            }
        },
        bottomBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (tab == Destination.HOME) {
                    ConnectionImportActions(
                        onQuickImport = { if (tv) showImportMenu = true else quickImportFromClipboard() },
                        onScanQr = if (tv || !qrCameraAvailable) null else ::scanQr,
                        onTvPair = if (tv && subscriptions.isEmpty()) ({ showTvPairing = true }) else null,
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
                            selected == null -> { tab = Destination.SERVERS; showImportMenu = true }
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
                Destination.SERVERS -> serverPageState.SaveableStateProvider("servers") {
                    DisposableEffect(Unit) { onDispose { scanned.clear(); scannedWithAutoTuic.clear() } }
                    ServersPage(subscriptions, selectedMatch, pinnedSubId, favorites, latencies,
                        probing = probing, measuring = measuring, refreshing = refreshing, failed = refreshFailed,
                        onRefresh = { sub -> scope.launch { refresh(sub) } },
                        manage = { activeSubMenu = it.id },
                        onAutoProbe = { sub -> probeSubscription(sub, notify = false) },
                        onManualPing = { sub -> probeSubscription(sub, notify = true, tuicOnly = true) },
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
                        }, add = { showImportMenu = true }, openUrl = ::openUrl)
                }
                Destination.SUBSCRIPTIONS -> SubscriptionPage(currentSubscription = selectedMatch?.first) {
                    openUrl("https://go.veor.pro/")
                }
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
            speedBytesPerSecond = otaSpeed,
            readyToInstall = otaFile?.exists() == true,
            error = otaError,
            onLater = { showOtaSheet = false; otaDismissed = true },
            onUpdate = { softTap(); startOtaInstall() },
            onDownloadInBrowser = { availableUpdate?.downloadUrl?.let(::openUrl) },
            onCancelDownload = {
                otaDownloadJob?.cancel()
                otaDownload = false
                otaError = "Загрузка остановлена. Можно скачать через браузер."
            },
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
                if (tv) {
                    Text("На телевизоре выберите «Ссылка подписки» и введите адрес " +
                        "пультом или через клавиатуру телефона. Передача с телефона по QR пока недоступна.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (tv) funActionRow("Добавить с телефона", Icons.Default.QrCode) {
                    showImportMenu = false
                    showTvPairing = true
                }
                funActionRow("Ссылка подписки", Icons.Default.Link) {
                    showImportMenu = false; importMode = "url"
                }
                funActionRow("Из буфера обмена", Icons.Default.ContentPaste) {
                    showImportMenu = false; quickImportFromClipboard()
                }
                if (!tv && qrCameraAvailable) funActionRow("Сканировать QR", Icons.Default.QrCodeScanner) {
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

    if (exitDetails && vpnStatus == "connected" && exitNetwork != null) {
        val identity = exitNetwork!!
        AlertDialog(
            onDismissRequest = { exitDetails = false },
            title = { Text("Сеть выхода VPN") },
            text = {
                Text(identity.flag() + " " + identity.countryName() +
                    "\nОрганизация: " + identity.network +
                    (if (identity.asn.isNotBlank()) "\n" + identity.asn else "") +
                    "\nIP: " + identity.ip +
                    "\nИсточник: ipwho.is" +
                    "\n\nЭто оператор IP-сети выхода, а не обязательно название продавца VPS.")
            },
            confirmButton = {
                TextButton(onClick = { exitDetails = false }) { Text("Закрыть") }
            }
        )
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

    if (showTvPairing) {
        AlertDialog(
            onDismissRequest = { showTvPairing = false },
            title = { Text("Добавить с телефона") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.widthIn(max = 380.dp),
                ) {
                    Text("Телевизор и телефон должны быть в одной локальной сети. " +
                        "В Phantom на телефоне нажмите QR, отсканируйте код на ТВ " +
                        "и выберите подписку для отправки.",
                        style = MaterialTheme.typography.bodyMedium)
                    tvPairCode?.let { code ->
                        val image = remember(code) {
                            runCatching {
                                val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                                    code, com.google.zxing.BarcodeFormat.QR_CODE, 540, 540,
                                    mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
                                com.journeyapps.barcodescanner.BarcodeEncoder()
                                    .createBitmap(matrix).asImageBitmap()
                            }.getOrNull()
                        }
                        if (image != null) {
                            Surface(color = androidx.compose.ui.graphics.Color.White) {
                                Image(image, "QR-код передачи подписки",
                                    Modifier.size(284.dp).padding(8.dp))
                            }
                        } else Text("Не удалось сформировать QR",
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (tvPairCode == null && tvPairError == null) {
                        CircularProgressIndicator(Modifier.size(26.dp))
                        Text("Подготавливаем защищённое соединение…")
                    } else if (tvPairError == null) {
                        Text("Ожидание подписки с телефона…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    tvPairError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTvPairing = false }, modifier = Modifier.tvFocusRing()) {
                    Text("Закрыть")
                }
            }
        )
    }

    incomingPairCode?.let { code ->
        AlertDialog(
            onDismissRequest = { if (!pairingSending) incomingPairCode = null },
            title = { Text("Передать подписку на ТВ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Выберите подписку для передачи. Адрес и ключи будут " +
                        "зашифрованы; одноразовый QR не содержит ваших секретов.",
                        style = MaterialTheme.typography.bodySmall)
                    if (subscriptions.isEmpty()) {
                        Text("На телефоне пока нет подписок. Сначала добавьте подписку в Phantom.")
                    } else {
                        Column(
                            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            subscriptions.forEach { subscription ->
                                OutlinedButton(
                                    enabled = !pairingSending,
                                    onClick = {
                                        pairingSending = true
                                        pairingSendError = null
                                        scope.launch {
                                            try {
                                                val sent = TvSubscriptionPairing.send(context,
                                                    code, subscription.source,
                                                    subscription.customTitle ?: subscription.title)
                                                if (sent) {
                                                    incomingPairCode = null
                                                    snackbar.showSnackbar("Подписка передана и сохранена на телевизоре")
                                                } else {
                                                    pairingSendError = "Телевизор не смог сохранить подписку."
                                                }
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (error: Exception) {
                                                pairingSendError = error.message
                                                    ?: "Не удалось связаться с телевизором"
                                            } finally {
                                                pairingSending = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(subscriptionDisplayName(subscription, subscriptions),
                                        maxLines = 2)
                                }
                            }
                        }
                    }
                    if (pairingSending) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Отправка и проверка сохранения…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    pairingSendError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { incomingPairCode = null; pairingSendError = null },
                    enabled = !pairingSending
                ) { Text("Закрыть") }
            }
        )
    }

    qrSource?.let { source ->
        val bitmap = remember(source) {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(source,
                com.google.zxing.BarcodeFormat.QR_CODE, 512, 512,
                mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
            com.journeyapps.barcodescanner.BarcodeEncoder().createBitmap(matrix).asImageBitmap()
        }
        AlertDialog(onDismissRequest = { qrSource = null }, title = { Text("QR-код") },
            text = { Image(bitmap, "QR-код", Modifier.fillMaxWidth().aspectRatio(1f)) },
            confirmButton = { TextButton(onClick = { qrSource = null }) { Text("Закрыть") } })
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
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)) {
                    Text(sub.title, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                    val isRemote = sub.source.startsWith("https://", ignoreCase = true) ||
                        sub.source.startsWith("http://", ignoreCase = true)
                    if (sub.servers.any { ProtocolSupport.isSupported(it.scheme) }) {
                        funActionRow(if (isRemote || sub.servers.size > 1) "Проверить пинг всех серверов" else "Проверить пинг",
                            Icons.Default.Speed, enabled = sub.id !in probing) {
                            activeSubMenu = null
                            probeSubscription(sub)
                        }
                    }
                    if (isRemote) funActionRow("Обновить подписку", Icons.Default.Refresh, enabled = sub.id !in refreshing) {
                        activeSubMenu = null
                        scope.launch { refresh(sub) }
                    }
                    funActionRow(when {
                        isRemote -> "Копировать ссылку"
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
                    val payloadBytes = sub.source.toByteArray().size
                    if (payloadBytes in 1..2950) funActionRow("Показать QR-код", Icons.Default.QrCode) {
                        activeSubMenu = null
                        qrSource = sub.source
                    }
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
                    if (payloadBytes <= 100_000) funActionRow("Поделиться", Icons.Default.Share) {
                        activeSubMenu = null
                        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, sub.source)
                        context.startActivity(Intent.createChooser(share, null))
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
                        if (pinnedSubId == id) {
                            pinnedSubId = null
                            prefs.edit().remove("pinned_sub_id").apply()
                        }
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 55.dp)
        .tvFocusRing().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
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
    var autoApkUpdates by remember { mutableStateOf(prefs.getBoolean("auto_apk_updates", true)) }

    var showThemeGallery by remember { mutableStateOf(false) }
    var tileMessage by remember { mutableStateOf<String?>(null) }
    var fragment by remember { mutableStateOf(FragmentationOptions.load(context)) }
    var fragmentMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showPing by remember { mutableStateOf(false) }
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
    if (showPing) { PingSettingsScreen(onBack = { showPing = false }); return }
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
                    funActionRow("Пинг серверов", Icons.Default.Speed) { showPing = true }
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
                    Text(
                        "Канал обновлений · " + if (xyz.wraithnet.phantom.BuildConfig.UPDATE_CHANNEL == "beta") "Бета" else "Стабильный",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Автообновление по Wi-Fi", fontSize = 15.sp)
                            Text("Без разрыва активного VPN. Android может запросить подтверждение.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoApkUpdates, onCheckedChange = { enabled ->
                            autoApkUpdates = enabled
                            prefs.edit().putBoolean("auto_apk_updates", enabled).apply()
                            PhantomAutoUpdateWorker.schedule(context.applicationContext)
                        })
                    }
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
