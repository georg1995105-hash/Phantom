package xyz.wraithnet.phantom.ui.home

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import android.os.PersistableBundle
import android.content.Context
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import xyz.wraithnet.phantom.ui.PhantomCaptureActivity
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import xyz.wraithnet.phantom.service.VpnRuntimeBridge
import xyz.wraithnet.phantom.data.SubscriptionFetcher
import xyz.wraithnet.phantom.data.SubscriptionRefreshErrors
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
import xyz.wraithnet.phantom.ui.theme.phantomPageBrush
import xyz.wraithnet.phantom.ui.theme.phantomPageTop
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
    var tvPairGeneration by remember { mutableIntStateOf(0) }
    var tvPairCode by remember { mutableStateOf<String?>(null) }
    var tvPairError by remember { mutableStateOf<String?>(null) }
    var tvPairListener by remember { mutableStateOf<TvSubscriptionPairing.Listener?>(null) }
    var incomingPairCode by remember { mutableStateOf<String?>(null) }
    var pairingSending by remember { mutableStateOf(false) }
    var pairingSendError by remember { mutableStateOf<String?>(null) }
    val serverPageState = rememberSaveableStateHolder()
    val unifiedHomeListState = rememberLazyListState()
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
        val source = try {
            SubscriptionQuickImport.unwrap(raw)
        } catch (_: IllegalArgumentException) {
            val warning = "Неверная ссылка добавления подписки"
            if (importMode != null) importError = warning
            else scope.launch { snackbar.showSnackbar(warning) }
            completed?.invoke(false)
            return
        }
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
    // Read from current Compose configuration on EACH composition, not once at
    // app launch: tablets may rotate between opening Home and tapping Scan.
    val qrLaunchOrientation = if (LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE)
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
                    .setCaptureActivity(PhantomCaptureActivity::class.java)
                    .setPrompt("Наведите камеру на QR-код подписки")
                    // Portrait-only manifest and ZXing lock rotated tablets.
                    // Keep the orientation of the SCREEN that opened the scanner.
                    .setOrientationLocked(false)
                    .addExtra(PhantomCaptureActivity.EXTRA_CAPTURE_ORIENTATION, qrLaunchOrientation)
                    .setBeepEnabled(false)
            )
        }.onFailure {
            importMode = "url"
            importError = "Не удалось открыть камеру. Введите ссылку подписки."
        }
    }

    LaunchedEffect(externalImport, importBusy) {
        if (importBusy) return@LaunchedEffect
        externalImport?.takeIf { it.isNotBlank() }?.let { text ->
            // A browser can open a link while a prior import is finishing.
            // Consume it only when the existing pipeline can accept it.
            onExternalImportConsumed()
            if (TvSubscriptionPairing.isPairingCode(text)) {
                incomingPairCode = text
                pairingSendError = null
            } else {
                tab = Destination.HOME
                showTvPairing = false
                add(text)
            }
        }
    }

    LaunchedEffect(showTvPairing, tvPairGeneration) {
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
        val checkingNode = !quiet && PhantomVpnService.isTunnelActive(vpnStatus)
        val message = try {
            if (!quiet && vpnStatus == PhantomVpnService.STATUS_STARTING) {
                error("Подключение VPN ещё не завершено")
            }
            val vpnRoute = if (checkingNode) {
                ActiveVpnSubscriptionProxy.forProfile(context, activeNode?.raw.orEmpty())
                    ?: error("Запущенная нода не соответствует выбранной")
            } else null
            // Never silently use the physical network when an active-node
            // refresh fails. Background refreshes intentionally stay direct.
            val remote = SubscriptionFetcher.download(
                context, item.source, store.deviceId(), item, vpnRoute = vpnRoute,
                manual = !quiet)
            val parsed = SubscriptionParser.parse(remote.body,
                item.customTitle ?: remote.title ?: item.title)
            if (vpnRoute != null && !ActiveVpnSubscriptionProxy.isActive(context, vpnRoute)) {
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
        } catch (e: Exception) {
            refreshFailed = refreshFailed + item.id
            when {
                !quiet && vpnStatus == PhantomVpnService.STATUS_STARTING ->
                    "Дождитесь подключения VPN и повторите проверку"
                !quiet -> SubscriptionRefreshErrors.describe(e, checkingNode)
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
    var otaTransferMode by remember { mutableStateOf("") }
    var resumeInstallAfterPermission by remember { mutableStateOf(false) }
    var otaFile by remember { mutableStateOf<File?>(null) }
    var otaError by remember { mutableStateOf<String?>(null) }
    var otaDismissed by remember { mutableStateOf(false) }
    var showOtaSheet by remember { mutableStateOf(false) }
    var openBackupFromOta by remember { mutableStateOf(false) }
    // Only an explicit check from Settings may open the update sheet over Settings.
    var otaManuallyRequested by remember { mutableStateOf(false) }
    var lastOtaCheck by remember { mutableLongStateOf(0L) }
    var lastOtaAttempt by remember { mutableLongStateOf(0L) }
    var otaChannelEpoch by remember { mutableIntStateOf(0) }

    // Retry after network failures on a later foreground, instead of suppressing OTA for six hours.
    LaunchedEffect(refreshVersion, otaChannelEpoch) {
        val requestEpoch = otaChannelEpoch
        val now = System.currentTimeMillis()
        if (now - lastOtaCheck >= 6L * 60 * 60 * 1000L &&
            now - lastOtaAttempt >= 2L * 60 * 1000L) {
            lastOtaAttempt = now
            try {
                val result = PhantomUpdates.check(context)
                if (requestEpoch != otaChannelEpoch) return@LaunchedEffect
                lastOtaCheck = System.currentTimeMillis()
                if (result?.version != availableUpdate?.version) {
                    otaFile = null
                    otaDismissed = false
                }
                availableUpdate = result
                if (result != null && !otaDismissed) {
                    // Switching channels from Settings should show a newly
                    // available update right away, without leaving Settings.
                    otaManuallyRequested = requestEpoch > 0
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
        val requestEpoch = otaChannelEpoch
        scope.launch {
            try {
                val result = PhantomUpdates.check(context)
                if (requestEpoch != otaChannelEpoch) return@launch
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
        // Do not allow a previously shown beta to install after switching back to Stable.
        if (!PhantomUpdates.channelAllows(release, PhantomUpdates.selectedChannel(context))) {
            availableUpdate = null
            showOtaSheet = false
            return
        }
        otaError = null
        resumeInstallAfterPermission = false
        otaDownloadJob = scope.launch {
            try {
                val file = otaFile?.takeIf { it.exists() } ?: run {
                    otaDownload = true
                    otaProgress = 0
                    otaSpeed = 0L
                    otaTransferMode = ""
                    PhantomUpdates.download(context, release,
                        onProgress = { otaProgress = it },
                        onSpeed = { otaSpeed = it },
                        onStatus = { otaTransferMode = it })
                        .also { otaFile = it }
                }
                // Protect against a channel change during the download.
                if (!PhantomUpdates.channelAllows(release,
                        PhantomUpdates.selectedChannel(context))) {
                    otaError = "Канал обновлений изменён. Проверьте новую версию."
                    return@launch
                }
                val launched = PhantomUpdates.openInstaller(context, file)
                if (!launched) {
                    resumeInstallAfterPermission = true
                    otaError = "Разрешите установку из Phantom — затем установка продолжится сама"
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

    // The first permission grant should not force the user to press Install twice.
    // The APK has already been reconstructed and verified before this point.
    val installLifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(installLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && resumeInstallAfterPermission &&
                Build.VERSION.SDK_INT >= 26 &&
                context.packageManager.canRequestPackageInstalls()) {
                resumeInstallAfterPermission = false
                val verified = otaFile?.takeIf { it.isFile }
                if (verified != null && availableUpdate?.let {
                        PhantomUpdates.channelAllows(it, PhantomUpdates.selectedChannel(context))
                    } == true) {
                    scope.launch {
                        try {
                            if (PhantomUpdates.openInstaller(context, verified)) {
                                otaError = null
                            }
                        } catch (e: Exception) {
                            otaError = e.message ?: "Не удалось открыть установщик"
                        }
                    }
                }
            }
        }
        installLifecycle?.addObserver(observer)
        onDispose { installLifecycle?.removeObserver(observer) }
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Установите браузер для открытия ссылки") }
        }
    }

    // TV and real tablets have their own wide Home, not a stretched phone page.
    val tv = isTelevision()
    val wideTablet = !tv &&
        LocalConfiguration.current.smallestScreenWidthDp >= 600
    val onServerPicked: (Subscription, ServerProfile) -> Unit = { owner, server ->
                            if (ProtocolSupport.isSupported(server.scheme)) {
                                val changed = selectedMatch?.first?.id != owner.id || selected?.id != server.id ||
                                    PhantomVpnService.isTunnelActive(vpnStatus) &&
                                        VpnRuntimeBridge.snapshot(context).profileDigest !=
                                            ActiveVpnSubscriptionProxy.digestForProfile(server.raw)
                                val ownerChanged = selectedMatch?.first?.id != owner.id
                                softTap()
                                selectedId = server.id
                                selectedSubId = owner.id
                                prefs.edit().putString("selected_server_id", server.id)
                                    .putString("selected_subscription_id", owner.id).apply()
                                if (ownerChanged && !tv && !wideTablet) {
                                    scope.launch { unifiedHomeListState.scrollToItem(0) }
                                }
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
                                if (changed && vpnStatus in setOf("connected", "degraded", "starting")) onStartVpn(server.raw)
                            } else scope.launch { snackbar.showSnackbar("Этот протокол пока не поддерживается") }
                        }
    BackHandler(enabled = tab != Destination.HOME) { tab = Destination.HOME }
    var showErrorDetails by remember { mutableStateOf(false) }
    LaunchedEffect(tv, tab) {
        if (tv && tab == Destination.SERVERS) tab = Destination.HOME
    }
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
        // The status inset is outside the content gradient. Paint it with the
        // gradient's first stop, preserving the existing safe-area geometry.
        containerColor = if (tv) MaterialTheme.colorScheme.background
            else MaterialTheme.colorScheme.phantomPageTop(),
        modifier = if (tv) Modifier.padding(horizontal = 10.dp, vertical = 8.dp) else Modifier,
        topBar = {
            if (!tv && tab != Destination.HOME && tab != Destination.SERVERS)
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
            // Only the tab strip is fixed. All Home content, including import actions
            // and every provider/server row, belongs to one parent LazyColumn.
            if (!tv) TabBar(tab) { next ->
                tab = next
                // Returning to Home or tapping its selected tab starts at the top
                // of the SAME lazy page. No server-only nested scrolling.
                if (next == Destination.HOME) {
                    scope.launch { unifiedHomeListState.scrollToItem(0) }
                }
            }
        }
    ) { inner ->
        val colors = MaterialTheme.colorScheme
        Row(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)
            .background(colors.phantomPageBrush())) {
            if (tv) TvSideNavigation(tab) { tab = it }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (tv && tab != Destination.HOME) PageTitle(
                    tab,
                    exit = if (vpnStatus == "connected") exitNetwork else null,
                    checkingExit = vpnStatus == "connected" && exitLoading,
                    openExit = { exitDetails = true },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                Destination.HOME, Destination.SERVERS -> {
                    if ((tv || wideTablet) && tab == Destination.HOME) {
                        WideHomeDashboard(
                            status = vpnStatus,
                            selected = selectedMatch,
                            download = downloadBytes,
                            upload = uploadBytes,
                            seconds = connectedSeconds,
                            subscriptions = subscriptions,
                            pinnedId = pinnedSubId,
                            favorites = favorites,
                            latency = latencies,
                            measuring = measuring,
                            refreshing = refreshing,
                            probing = probing,
                            failed = refreshFailed,
                            importing = importBusy,
                            focusEpoch = refreshVersion,
                            modalVisible = showTvPairing || showImportMenu ||
                                importMode != null || qrSource != null || activeSubMenu != null,
                            onToggle = {
                                softTap()
                                when {
                                    PhantomVpnService.isTunnelActive(vpnStatus) -> onStopVpn()
                                    selected == null -> showImportMenu = true
                                    else -> onStartVpn(selected.raw)
                                }
                            },
                            onSelect = onServerPicked,
                            onRefresh = { sub -> scope.launch { refresh(sub) } },
                            onManage = { activeSubMenu = it.id },
                            onProbe = { sub -> probeSubscription(sub, notify = false) },
                            onManualPing = { sub -> probeSubscription(sub, notify = true) },
                            onFavorite = { id ->
                                favorites = if (id in favorites) favorites - id else favorites + id
                                prefs.edit().putStringSet("favorite_servers", favorites).apply()
                            },
                            onImport = { showImportMenu = true },
                            onPairQr = if (tv) ({ showTvPairing = true }) else null,
                            onScanQr = if (!tv && qrCameraAvailable) ::scanQr else null,
                            onShowError = if (vpnStatus in setOf("error", "degraded") && vpnError.isNotBlank())
                                ({ showErrorDetails = true }) else null,
                            onOpenUrl = ::openUrl,
                            exit = if (vpnStatus == "connected") exitNetwork else null,
                            checkingExit = vpnStatus == "connected" && exitLoading,
                            openExit = { exitDetails = true },
                        )
                    } else {
                        serverPageState.SaveableStateProvider("servers") {
                            DisposableEffect(Unit) {
                                onDispose { scanned.clear(); scannedWithAutoTuic.clear() }
                            }
                    ServersPage(subscriptions, selectedMatch, pinnedSubId, favorites, latencies,
                        probing = probing, measuring = measuring, refreshing = refreshing, failed = refreshFailed,
                        onRefresh = { sub -> scope.launch { refresh(sub) } },
                        manage = { activeSubMenu = it.id },
                        onAutoProbe = { sub -> probeSubscription(sub, notify = false) },
                        onManualPing = { sub -> probeSubscription(sub, notify = true) },
                        favorite = { id ->
                            favorites = if (id in favorites) favorites - id else favorites + id
                            prefs.edit().putStringSet("favorite_servers", favorites).apply()
                        }, choose = onServerPicked, openUrl = ::openUrl,
                        header = if (tv) null else ({
                            Column {
                                PageTitle(Destination.HOME,
                                    exit = if (vpnStatus == "connected") exitNetwork else null,
                                    checkingExit = vpnStatus == "connected" && exitLoading,
                                    openExit = { exitDetails = true })
                                ConnectionPage(vpnStatus, connectedSeconds,
                                                    toggle = {
                                                        softTap()
                                                        when {
                                                            PhantomVpnService.isTunnelActive(vpnStatus) -> onStopVpn()
                                                            selected == null -> { showImportMenu = true }
                                                            else -> onStartVpn(selected.raw)
                                                        }
                                                    },
                                                    error = if (vpnStatus in setOf("error", "degraded") && vpnError.isNotBlank()) ({ showErrorDetails = true }) else null,
                                                    hasSubscriptions = subscriptions.isNotEmpty(),
                                                    tvFocusEpoch = refreshVersion,
                                                    tvModalVisible = showTvPairing || showImportMenu ||
                                                        importMode != null || qrSource != null || activeSubMenu != null,
                                    embedded = true)
                                if (subscriptions.isEmpty()) {
                                    ConnectionImportActions(
                                        onOpenImport = { showImportMenu = true },
                                        onScanQr = if (qrCameraAvailable) ::scanQr else null,
                                        hasSubscriptions = false,
                                        importing = importBusy)
                                }
                                // Subscription cards start directly under the connection
                                // status; no extra country card or redundant section title.
                            }
                        }),
                        footer = if (tv || subscriptions.isEmpty()) null else ({
                            ConnectionImportActions(
                                onOpenImport = { showImportMenu = true },
                                onScanQr = if (qrCameraAvailable) ::scanQr else null,
                                hasSubscriptions = true,
                                importing = importBusy)
                        }),
                        listState = if (tv) null else unifiedHomeListState)
                        }
                    }
                }
                Destination.SUBSCRIPTIONS -> SubscriptionPage(currentSubscription = selectedMatch?.first) {
                    openUrl("https://go.veor.pro/")
                }
                Destination.SETTINGS -> PhantomSettings(
                    onThemeChange = onThemeChange,
                    openBackupRequest = openBackupFromOta,
                    onBackupRequestHandled = { openBackupFromOta = false },
                    onCheckAppUpdate = ::checkAppUpdateNow,
                    onUpdateChannelChanged = {
                        otaDownloadJob?.cancel()
                        availableUpdate = null
                        otaFile = null
                        resumeInstallAfterPermission = false
                        otaTransferMode = ""
                        showOtaSheet = false
                        otaDismissed = false
                        otaManuallyRequested = false
                        lastOtaCheck = 0L
                        lastOtaAttempt = 0L
                        otaChannelEpoch++ // Fresh check; ignore the six-hour cache.
                    },
                    onBackupRestored = {
                        applySubscriptions(store.read())
                        selectedId = prefs.getString("selected_server_id", null)
                        selectedSubId = prefs.getString("selected_subscription_id", null)
                        pinnedSubId = prefs.getString("pinned_sub_id", null)
                        favorites = prefs.getStringSet("favorite_servers", emptySet()).orEmpty().toSet()
                        runCatching { PhantomThemeMode.valueOf(prefs.getString("theme", "FOREST") ?: "FOREST") }
                            .getOrNull()?.let(onThemeChange)
                        PhantomAutoUpdateWorker.schedule(context.applicationContext)
                        otaDownloadJob?.cancel()
                        availableUpdate = null
                        otaFile = null
                        resumeInstallAfterPermission = false
                        otaTransferMode = ""
                        showOtaSheet = false
                        otaDismissed = false
                        otaManuallyRequested = false
                        lastOtaCheck = 0L
                        lastOtaAttempt = 0L
                        otaChannelEpoch++
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
            transferMode = otaTransferMode,
            error = otaError,
            onLater = { showOtaSheet = false; otaDismissed = true },
            onUpdate = { softTap(); startOtaInstall() },
            onDownloadInBrowser = { availableUpdate?.downloadUrl?.let(::openUrl) },
            signingMismatch = PhantomUpdates.hasOfficialSigningCertificate(context) == false,
            onOpenBackup = {
                showOtaSheet = false
                otaDismissed = true
                openBackupFromOta = true
                tab = Destination.SETTINGS
            },
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
                    Text("Чтобы отправить подписку с телефона, выберите «Добавить с телефона» " +
                        "и отсканируйте QR-код телевизора в Phantom. Также можно ввести URL пультом.",
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
                        Text("QR действует 2 минуты · Ожидание подписки с телефона…",
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
            },
            dismissButton = {
                TextButton(onClick = { tvPairGeneration++ }, modifier = Modifier.tvFocusRing()) {
                    Text("Обновить QR")
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
                if (selectedMatch?.first?.id == id && PhantomVpnService.isTunnelActive(vpnStatus)) onStopVpn()
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tv = isTelevision()
    Row(modifier.fillMaxWidth().heightIn(min = if (tv) 48.dp else 55.dp)
        .tvFocusRing().clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = if (tv) 12.dp else 16.dp),
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
    onUpdateChannelChanged: () -> Unit,
    onBackupRestored: () -> Unit,
    openBackupRequest: Boolean = false,
    onBackupRequestHandled: () -> Unit = {}
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
    var betaApkUpdates by remember { mutableStateOf(
        prefs.getBoolean(PhantomUpdates.BETA_UPDATES_PREF, false)) }

    var showThemeGallery by rememberSaveable { mutableStateOf(false) }
    var tileMessage by remember { mutableStateOf<String?>(null) }
    var fragment by remember { mutableStateOf(FragmentationOptions.load(context)) }
    var fragmentMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showPing by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showDns by remember { mutableStateOf(false) }

    var showComponents by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    LaunchedEffect(openBackupRequest) {
        if (openBackupRequest) {
            showBackup = true
            onBackupRequestHandled()
        }
    }
    val tv = isTelevision()
    var group by rememberSaveable { mutableStateOf("") }
    var restoreTvFocus by remember { mutableStateOf("") }
    val themeFocus = remember { FocusRequester() }
    val appsFocus = remember { FocusRequester() }
    var themeHasFocus by remember { mutableStateOf(false) }
    var appsHasFocus by remember { mutableStateOf(false) }
    val connectionFocus = remember { FocusRequester() }
    val dnsFocus = remember { FocusRequester() }
    val pingFocus = remember { FocusRequester() }
    val backgroundFocus = remember { FocusRequester() }
    var backgroundHasFocus by remember { mutableStateOf(false) }
    val backupFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    var connectionHasFocus by remember { mutableStateOf(false) }
    var dnsHasFocus by remember { mutableStateOf(false) }
    var pingHasFocus by remember { mutableStateOf(false) }
    var backupHasFocus by remember { mutableStateOf(false) }
    var aboutHasFocus by remember { mutableStateOf(false) }
    var restoreAboutFocus by remember { mutableStateOf("") }
    BackHandler(enabled = group.isNotEmpty()) {
        if (tv) restoreTvFocus = group
        group = ""
    }
    val settingsScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(tv, group) {
        if (group == "background" && restoreTvFocus.isBlank()) settingsScroll.animateScrollToItem(2)
        if (tv && group == "network" && restoreTvFocus.isBlank()) settingsScroll.animateScrollToItem(0)
    }
    if (showThemeGallery) {
        ThemeGalleryScreen(
            selected = mode,
            onChoose = { chosen -> mode = chosen; onThemeChange(chosen) },
            onBack = { showThemeGallery = false; restoreTvFocus = "theme" }
        )
        return
    }
    if (showLog) { DiagnosticsScreen(onBack = { showLog = false; restoreAboutFocus = "log" }); return }
    if (showPing) { PingSettingsScreen(onBack = { showPing = false; restoreTvFocus = "ping" }); return }
    if (showApps) { AppRoutingScreen(onBack = { showApps = false; restoreTvFocus = "apps" }); return }
    if (showDns) { DnsSettingsScreen(onBack = { showDns = false; restoreTvFocus = "dns" }); return }

    if (showBackup) {
        BackupScreen(
            onBack = { showBackup = false; restoreTvFocus = "backup" },
            onRestored = {
                showBackup = false
                onBackupRestored()
            }
        )
        return
    }
    if (showComponents) {
        ComponentsScreen(onBack = { showComponents = false; restoreAboutFocus = "components" })
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
    fun toggle(name: String) { group = if (group == name) "" else name }
    if (tv && group == "about") {
        TvAboutSettings(
            version = version,
            autoApkUpdates = autoApkUpdates,
            betaApkUpdates = betaApkUpdates,
            onToggleBeta = { enabled ->
                betaApkUpdates = enabled
                PhantomUpdates.setBetaEnabled(context, enabled)
                onUpdateChannelChanged()
            },
            onToggleAuto = { enabled ->
                autoApkUpdates = enabled
                prefs.edit().putBoolean("auto_apk_updates", enabled).apply()
                PhantomAutoUpdateWorker.schedule(context.applicationContext)
            },
            onCheckUpdate = onCheckAppUpdate,
            onComponents = { showComponents = true },
            onLog = { showLog = true },
            focusOnReturn = restoreAboutFocus,
            onFocusRestored = { restoreAboutFocus = "" },
            onBack = { group = ""; restoreTvFocus = "about" },
        )
        return
    }
    // Child screens replace this LazyColumn; focus may otherwise return to the
    // left navigation rail after BACK on a real remote.
    LaunchedEffect(tv, restoreTvFocus) {
        if (!tv || restoreTvFocus.isBlank()) return@LaunchedEffect
        val destination = restoreTvFocus
        // BACK may collapse the network group on some TV firmware before the
        // child handler disposes its screen. Without reopening it there is no
        // DNS/ping focus target to restore to.
        if (destination == "dns" || destination == "ping") {
            if (group != "network") {
                group = "network"
                withFrameNanos { }
            }
        }
        // LazyColumn may not yet have mounted the target item after leaving a
        // child screen. A single request silently fails or targets the TV rail.
        val targetIndex = when (destination) {
            "network", "dns", "ping" -> 0
            "theme", "apps" -> 1
            "backup", "background" -> 2
            else -> 3
        }
        repeat(24) {
            settingsScroll.scrollToItem(targetIndex)
            withFrameNanos { }
            val requested = runCatching {
                when (destination) {
                    "network" -> connectionFocus.requestFocus()
                    "dns" -> dnsFocus.requestFocus()
                    "ping" -> pingFocus.requestFocus()
                    "theme" -> themeFocus.requestFocus()
                    "apps" -> appsFocus.requestFocus()
                    "background" -> backgroundFocus.requestFocus()
                    "backup" -> backupFocus.requestFocus()
                    "about" -> aboutFocus.requestFocus()
                }
                // Compose FocusRequester.requestFocus() returns Unit on the
                // pinned version; success means no exception, while the
                // onFocusChanged state below proves focus was actually restored.
                true
            }.getOrDefault(false)
            withFrameNanos { }
            val restored = when (destination) {
                "network" -> connectionHasFocus
                "dns" -> dnsHasFocus
                "ping" -> pingHasFocus
                "theme" -> themeHasFocus
                "apps" -> appsHasFocus
                "background" -> backgroundHasFocus
                "backup" -> backupHasFocus
                "about" -> aboutHasFocus
                else -> true
            }
            // Do not accept an old hasFocus=true from the row we navigated
            // away from: the request must succeed on the NEW mounted row.
            if (requested && restored) {
                restoreTvFocus = ""
                return@LaunchedEffect
            }
            delay(75)
        }
        restoreTvFocus = ""
    }
    // The same action handlers and preference keys back the touch and TV layouts.
    // Expanded controls occupy their own full-width row, never a narrow tile.
    val twoColumns = LocalConfiguration.current.screenWidthDp >= 320 &&
        androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 840.dp).fillMaxSize(), state = settingsScroll,
            contentPadding = PaddingValues(horizontal = if (tv) 10.dp else 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "settings-network") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTile("Подключение", "DNS, фрагментация TLS", Icons.Default.Tune,
                        modifier = Modifier.focusRequester(connectionFocus)
                            .onFocusChanged { connectionHasFocus = it.hasFocus },
                        wide = true, expanded = group == "network") { toggle("network") }
                    if (group == "network") Panel {
                    funActionRow("DNS", Icons.Default.Dns,
                        modifier = Modifier.focusRequester(dnsFocus)
                            .onFocusChanged { dnsHasFocus = it.hasFocus }) {
                        dnsHasFocus = false
                        showDns = true
                    }
                    funActionRow("Пинг серверов", Icons.Default.Speed,
                        modifier = Modifier.focusRequester(pingFocus)
                            .onFocusChanged { pingHasFocus = it.hasFocus }) {
                        pingHasFocus = false
                        showPing = true
                    }
                    // Keep the existing touch-only Switch behavior on phones/tablets.
                    // A remote can toggle the entire row on Android TV.
                    val onFragmentToggle: (Boolean) -> Unit = { enabled ->
                        val next = fragment.copy(enabled = enabled)
                        if (next.isValid()) {
                            fragment = next
                            FragmentationOptions.save(context, next)
                            fragmentMessage = "При следующем подключении изменения вступят в силу"
                        } else {
                            fragmentMessage = "Исправьте значения фрагментации"
                        }
                    }
                    Row(
                        (if (tv) Modifier.fillMaxWidth().heightIn(min = 66.dp)
                            .tvFocusRing()
                            .toggleable(value = fragment.enabled, role = Role.Switch,
                                onValueChange = onFragmentToggle)
                        else Modifier.fillMaxWidth())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Фрагментация TLS", fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Для TCP-соединений с TLS/REALITY. На Hysteria2 не действует.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = fragment.enabled,
                            onCheckedChange = if (tv) null else onFragmentToggle)
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
            item(key = "settings-primary") {
                SettingsTileRow(twoColumns,
                    first = {
                        SettingsTile("Тема оформления", mode.title, Icons.Default.Palette,
                            modifier = Modifier.focusRequester(themeFocus)
                                .onFocusChanged { themeHasFocus = it.hasFocus },
                            wide = !twoColumns) {
                            themeHasFocus = false
                            showThemeGallery = true
                        }
                    },
                    second = {
                        if (tv) SettingsTile("Туннелирование", "Выбор приложений", Icons.Default.Layers,
                            modifier = Modifier.focusRequester(appsFocus)
                                .onFocusChanged { appsHasFocus = it.hasFocus },
                            wide = !twoColumns) {
                            appsHasFocus = false
                            showApps = true
                        }
                        else SettingsTile("Быстрый доступ", "Шторка Android", Icons.Default.PowerSettingsNew,
                            wide = !twoColumns) {
                            PhantomQuickTileService.offerToAdd(context) { tileMessage = it }
                        }
                    })
                tileMessage?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }
            item(key = "settings-runtime") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTileRow(twoColumns,
                        first = {
                            if (tv) SettingsTile("Резервная копия", "Сохранение и восстановление", Icons.Default.Backup,
                                modifier = Modifier.focusRequester(backupFocus)
                                    .onFocusChanged { backupHasFocus = it.hasFocus },
                                wide = !twoColumns) {
                                backupHasFocus = false
                                showBackup = true
                            } else SettingsTile("Туннелирование", "Выбор приложений", Icons.Default.Layers,
                                wide = !twoColumns) { showApps = true }
                        },
                        second = {
                            SettingsTile("Фоновая работа", "Экран, батарея, всегда включённый VPN", Icons.Default.BatteryStd,
                                modifier = Modifier.focusRequester(backgroundFocus)
                                    .onFocusChanged { backgroundHasFocus = it.hasFocus },
                                wide = !twoColumns, expanded = group == "background") { toggle("background") }
                        })
                    if (group == "background") Panel {
                    Row(Modifier.fillMaxWidth().tvFocusRing().clickable {
                        keepActive = !keepActive
                        prefs.edit().putBoolean("keep_active", keepActive).apply()
                        if (VpnRuntimeBridge.snapshot(context).running) {
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
                        Switch(checked = keepActive, onCheckedChange = if (tv) null else { enabled ->
                            keepActive = enabled
                            prefs.edit().putBoolean("keep_active", enabled).apply()
                            if (VpnRuntimeBridge.snapshot(context).running) {
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
            item(key = "settings-about") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tv) SettingsTile("О приложении", "Phantom $version", Icons.Default.Info,
                        modifier = Modifier.focusRequester(aboutFocus)
                            .onFocusChanged { aboutHasFocus = it.hasFocus }, wide = true) {
                        aboutHasFocus = false
                        toggle("about")
                    } else SettingsTileRow(twoColumns,
                        first = {
                            SettingsTile("Резервная копия", "Сохранение и восстановление", Icons.Default.Backup,
                                wide = !twoColumns) { showBackup = true }
                        },
                        second = {
                            SettingsTile("О приложении", "Phantom $version", Icons.Default.Info,
                                wide = !twoColumns, expanded = group == "about") { toggle("about") }
                        })
                    if (!tv && group == "about") Panel {
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Получать бета-обновления", fontSize = 15.sp)
                            Text("Ранние тестовые версии. Возврат на стабильную — при следующем подходящем обновлении.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = betaApkUpdates, onCheckedChange = { enabled ->
                            betaApkUpdates = enabled
                            PhantomUpdates.setBetaEnabled(context, enabled)
                            onUpdateChannelChanged()
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
            item(key = "settings-boot") {
                val changeBoot: (Boolean) -> Unit = { enabled ->
                    bootConnect = enabled
                    prefs.edit().putBoolean(VpnBootPolicy.PREF_KEY, enabled).apply()
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f))) {
                    Row(Modifier.fillMaxWidth().tvFocusRing()
                        .toggleable(value = bootConnect, role = Role.Switch, onValueChange = changeBoot)
                        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsTileIcon(Icons.Default.Refresh)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Автоподключение", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("После перезагрузки · последний сервер",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Text("Сначала разрешите VPN в Android.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = bootConnect, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTileRow(twoColumns: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (twoColumns) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) { first() }
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            first()
            second()
        }
    }
}

@Composable
private fun SettingsTileIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(25.dp), tint = c.primary)
    }
}

@Composable
private fun SettingsTile(
    title: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier, wide: Boolean = false, expanded: Boolean = false,
    action: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val layout = if (wide) Modifier.fillMaxWidth().heightIn(min = 76.dp)
        else Modifier.fillMaxSize().heightIn(min = 120.dp)
    Surface(modifier = modifier.then(layout).tvFocusRing(shape)
        .clip(shape).clickable(role = Role.Button, onClick = action),
        shape = shape, color = c.surface,
        border = BorderStroke(1.dp, if (expanded) c.primary else c.outline.copy(alpha = .55f))) {
        if (wide) Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsTileIcon(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(summary, fontSize = 12.sp, color = c.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, null,
                tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        } else Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingsTileIcon(icon)
                Spacer(Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, null,
                    tint = c.onSurfaceVariant, modifier = Modifier.size(17.dp))
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
            Text(summary, fontSize = 12.sp, lineHeight = 15.sp, color = c.onSurfaceVariant)
        }
    }
}

/**
 * Dedicated television About screen. On the phone the original inline
 * expandable section is retained; on TV hiding actions below the fold made
 * "About" appear empty when using a D-pad.
 */
@Composable
private fun TvAboutSettings(
    version: String,
    autoApkUpdates: Boolean,
    betaApkUpdates: Boolean,
    onToggleBeta: (Boolean) -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onComponents: () -> Unit,
    onLog: () -> Unit,
    focusOnReturn: String,
    onFocusRestored: () -> Unit,
    onBack: () -> Unit,
) {
    val componentsFocus = remember { FocusRequester() }
    val logFocus = remember { FocusRequester() }
    var componentsHasFocus by remember { mutableStateOf(false) }
    var logHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(focusOnReturn) {
        if (focusOnReturn.isBlank()) return@LaunchedEffect
        repeat(16) {
            withFrameNanos { }
            runCatching {
                when (focusOnReturn) {
                    "components" -> componentsFocus.requestFocus()
                    "log" -> logFocus.requestFocus()
                }
            }
            withFrameNanos { }
            if ((focusOnReturn == "components" && componentsHasFocus) ||
                (focusOnReturn == "log" && logHasFocus)) {
                onFocusRestored()
                return@LaunchedEffect
            }
            delay(80)
        }
        onFocusRestored()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack, modifier = Modifier.tvFocusRing()) {
            Icon(Icons.Default.ArrowBack, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("К настройкам")
        }
        Text("О приложении", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Panel {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("PHANTOM VPN", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Версия ${version}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .22f))
            Row(
                Modifier.fillMaxWidth().tvFocusRing().clickable {
                    onToggleAuto(!autoApkUpdates)
                }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Автообновление по Wi-Fi", fontWeight = FontWeight.Medium)
                    Text("Установка поверх Phantom с сохранением подписок и настроек.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoApkUpdates, onCheckedChange = null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .22f))
            Row(
                Modifier.fillMaxWidth().tvFocusRing().clickable {
                    onToggleBeta(!betaApkUpdates)
                }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Получать бета-обновления", fontWeight = FontWeight.Medium)
                    Text("Ранние тестовые версии. На стабильную — при следующем обновлении.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = betaApkUpdates, onCheckedChange = null)
            }
            funActionRow("Проверить обновление приложения", Icons.Default.SystemUpdateAlt,
                onClick = onCheckUpdate)
            funActionRow("Компоненты и лицензии", Icons.Default.Layers,
                modifier = Modifier.focusRequester(componentsFocus)
                    .onFocusChanged { componentsHasFocus = it.hasFocus },
                onClick = onComponents)
            funActionRow("Журнал VPN", Icons.Default.List,
                modifier = Modifier.focusRequester(logFocus)
                    .onFocusChanged { logHasFocus = it.hasFocus },
                onClick = onLog)
        }
    }
}
