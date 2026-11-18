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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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
import xyz.wraithnet.phantom.data.ReleaseNotes
import xyz.wraithnet.phantom.data.CountryFlags
import xyz.wraithnet.phantom.data.FragmentationOptions
import java.io.File
import xyz.wraithnet.phantom.ui.update.PhantomUpdateSection
import xyz.wraithnet.phantom.ui.settings.ComponentsScreen
import xyz.wraithnet.phantom.ui.settings.BackupScreen
import xyz.wraithnet.phantom.ui.settings.AppRoutingScreen
import xyz.wraithnet.phantom.ui.settings.DnsSettingsScreen
import xyz.wraithnet.phantom.service.VpnBootPolicy
import xyz.wraithnet.phantom.ui.settings.DiagnosticsScreen
import xyz.wraithnet.phantom.ui.settings.ThemeGalleryScreen
import xyz.wraithnet.phantom.service.PhantomQuickTileService
import xyz.wraithnet.phantom.data.SubscriptionParser
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
    var subscriptions by remember { mutableStateOf(store.read()) }
    var selectedId by remember { mutableStateOf(prefs.getString("selected_server_id", null)) }
    var selectedSubId by remember { mutableStateOf(prefs.getString("selected_subscription_id", null)) }
    var pinnedSubId by remember { mutableStateOf(prefs.getString("pinned_sub_id", null)) }
    val selectedMatch = ProfileSelection.resolve(subscriptions, selectedSubId, selectedId, pinnedSubId)
    val selected = selectedMatch?.second
    var refreshing by remember { mutableStateOf(setOf<String>()) }
    var probing by remember { mutableStateOf(setOf<String>()) }
    val latencies = remember { mutableStateMapOf<String, Long>() }
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
    fun persist(list: List<Subscription>) {
        subscriptions = list
        store.write(list)
    }
    fun pingKey(owner: Subscription, server: ServerProfile): String = "${owner.id}:${server.id}"

    // For QUIC/UDP, show only real HTTPS latency measured over the active tunnel.
    LaunchedEffect(vpnStatus, connectedSinceElapsed, selectedSubId, selectedId, subscriptions) {
        if (vpnStatus == "connected") {
            selectedMatch?.let { (owner, server) ->
                val rtt = LatencyProbe.lastVerifiedTunnelLatency(context, server)
                if (rtt > 0L) latencies[pingKey(owner, server)] = rtt
            }
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
            val warning = "Такая подписка или ссылка уже добавлена"
            if (importMode != null) importError = warning
            else scope.launch { snackbar.showSnackbar(warning) }
            return
        }
        importBusy = true
        importError = null
        scope.launch {
            try {
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
                persist(subscriptions + subscription)
                if (selected == null) {
                    selectedId = subscription.servers.firstOrNull()?.id
                    selectedSubId = subscription.id
                    prefs.edit().putString("selected_server_id", selectedId)
                        .putString("selected_subscription_id", selectedSubId).apply()
                }
                importMode = null
                showImportMenu = false
                softTap()
                snackbar.showSnackbar("Конфигурация добавлена")
            } catch (e: Exception) {
                val message = e.message ?: "Не удалось добавить подписку"
                if (importMode != null) importError = message
                else snackbar.showSnackbar(message)
            } finally {
                importBusy = false
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { add(it) }
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
        try {
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
            persist(subscriptions.map { if (it.id == item.id) updated else it })
            refreshFailed = refreshFailed - item.id
            if (!quiet) {
                softTap()
                snackbar.showSnackbar("Подписка обновлена")
            }
        } catch (e: Exception) {
            refreshFailed = refreshFailed + item.id
            if (!quiet) snackbar.showSnackbar("Не удалось обновить подписку. Сохранённые серверы доступны.")
        } finally {
            refreshing = refreshing - item.id
        }
    }

    fun probeSubscription(item: Subscription) {
        if (item.id in probing) return
        probing = probing + item.id
        scope.launch {
            try {
                val servers = item.servers.filter { ProtocolSupport.isSupported(it.scheme) }
                coroutineScope {
                    val limit = Semaphore(3)
                    servers.map { server ->
                        async {
                            val ping = limit.withPermit {
                                runCatching { LatencyProbe.measure(context.applicationContext, server) }
                                    .getOrNull() ?: -1L
                            }
                            latencies[pingKey(item, server)] = ping
                            pingKey(item, server) to ping
                        }
                    }.awaitAll().forEach { (serverKey, delayMs) ->
                        latencies[serverKey] = delayMs
                    }
                }
                val checked = servers.count { (latencies[pingKey(item, it)] ?: -1L) > 0L }
                val needsTunnel = servers.any { ProtocolSupport.needsConnectedPing(it.scheme) }
                snackbar.showSnackbar(when {
                    checked > 0 -> "Проверка задержки завершена"
                    needsTunnel -> "Для точного пинга TUIC/Hysteria2 подключите сервер"
                    else -> "Не получено ни одного результата проверки"
                })
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Не удалось проверить задержку")
            } finally {
                probing = probing - item.id
            }
        }
    }

    LaunchedEffect(refreshVersion) {
        val stored = store.read()
        if (stored != subscriptions) subscriptions = stored
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
    var otaDownload by remember { mutableStateOf(false) }
    var otaProgress by remember { mutableIntStateOf(0) }
    var otaFile by remember { mutableStateOf<File?>(null) }
    var otaError by remember { mutableStateOf<String?>(null) }
    var otaDismissed by remember { mutableStateOf(false) }
    var showOtaSheet by remember { mutableStateOf(false) }
    var lastOtaCheck by remember { mutableLongStateOf(0L) }

    // Auto-check on launch/foreground (max once per six hours); no settings detour.
    LaunchedEffect(refreshVersion) {
        val now = System.currentTimeMillis()
        if (now - lastOtaCheck >= 6L * 60 * 60 * 1000L) {
            lastOtaCheck = now
            try {
                val result = PhantomUpdates.check(context)
                if (result?.version != availableUpdate?.version) {
                    otaFile = null
                    otaDismissed = false
                }
                availableUpdate = result
                if (result != null && !otaDismissed) showOtaSheet = true
                otaError = null
            } catch (_: Exception) {
                // Existing VPN functionality must not depend on the update service.
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
    Scaffold(
        topBar = {
            Box(Modifier.statusBarsPadding()) { PageTitle(tab) }
        },
        bottomBar = { TabBar(tab) { tab = it } }
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
                    error = if (vpnStatus == "error" && vpnError.isNotBlank()) ({ showErrorDetails = true }) else null)
                Destination.SERVERS -> ServersPage(subscriptions, selectedMatch, pinnedSubId, favorites, latencies,
                    favorite = { id ->
                        favorites = if (id in favorites) favorites - id else favorites + id
                        prefs.edit().putStringSet("favorite_servers", favorites).apply()
                    }, choose = { owner, server ->
                        if (ProtocolSupport.isSupported(server.scheme)) {
                            val changed = selectedMatch?.first?.id != owner.id || selected?.id != server.id
                            softTap()
                            selectedId = server.id
                            selectedSubId = owner.id
                            prefs.edit().putString("selected_server_id", server.id)
                                .putString("selected_subscription_id", owner.id).apply()
                            if (changed && vpnStatus in setOf("connected", "starting")) onStartVpn(server.raw)
                        } else scope.launch { snackbar.showSnackbar("Этот протокол пока не поддерживается") }
                    }, add = { tab = Destination.SUBSCRIPTIONS; showImportMenu = true })
                Destination.SUBSCRIPTIONS -> SubscriptionPage(subscriptions.sortedByDescending { it.id == pinnedSubId },
                    selectedMatch?.first?.id, refreshing, refreshFailed, { scope.launch { refresh(it) } },
                    { activeSubMenu = it.id }, ::probeSubscription, probing, { showImportMenu = true },
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
                    onThemeChange = onThemeChange, onBackupRestored = {
                        subscriptions = store.read()
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
    if (showOtaSheet && availableUpdate != null && tab != Destination.SETTINGS &&
        !showImportMenu && activeSubMenu == null && importMode == null) {
        val release = availableUpdate!!
        ModalBottomSheet(
            onDismissRequest = { showOtaSheet = false; otaDismissed = true },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdateAlt, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Обновление " + release.version,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showOtaSheet = false; otaDismissed = true }) {
                        Text("Позже")
                    }
                }
                val notes = ReleaseNotes.summary(release.notes, 5)
                if (notes.isNotBlank()) {
                    Text("Что изменилось", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(notes, fontSize = 12.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (otaDownload) {
                    LinearProgressIndicator(
                        progress = { otaProgress.toFloat() / 100f },
                        modifier = Modifier.fillMaxWidth())
                    Text("Загрузка " + otaProgress + "%", fontSize = 12.sp)
                }
                otaError?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { softTap(); startOtaInstall() },
                    enabled = !otaDownload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (otaDownload) "Скачивание…" else if (otaFile?.exists() == true)
                        "Установить" else "Скачать и установить")
                }
                Spacer(Modifier.height(4.dp))
            }
        }
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
                    showImportMenu = false; add(pasteText())
                }
                funActionRow("Сканировать QR", Icons.Default.QrCodeScanner) {
                    showImportMenu = false
                    scanLauncher.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setCaptureActivity(
                                xyz.wraithnet.phantom.ui.PhantomCaptureActivity::class.java)
                            .setPrompt("Поместите QR-код в рамку")
                            .setOrientationLocked(true)
                            .setBeepEnabled(false)
                    )
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
                persist(subscriptions.filterNot { it.id == id })
                favorites = favorites.filterNot { it.startsWith("$id:") }.toSet()
                prefs.edit().putStringSet("favorite_servers", favorites).apply()
                deleteId = null
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
        .clickable(onClick = onClick).padding(horizontal = 16.dp),
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
                    funActionRow("Компоненты и лицензии", Icons.Default.Layers) { showComponents = true }
                    funActionRow("Журнал VPN", Icons.Default.List) { showLog = true }
                    PhantomUpdateSection()
                }
            }
        }
    }
}

@Composable
private fun SettingsAction(title: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null)
    }
}
