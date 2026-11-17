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
import org.json.JSONArray
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import xyz.wraithnet.phantom.BuildConfig
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionFetcher
import xyz.wraithnet.phantom.data.LatencyProbe
import xyz.wraithnet.phantom.data.ProtocolSupport
import xyz.wraithnet.phantom.data.ProfileSelection
import xyz.wraithnet.phantom.data.TrafficRates
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
import xyz.wraithnet.phantom.ui.theme.themeScheme
import xyz.wraithnet.phantom.service.PhantomQuickTileService
import androidx.compose.foundation.isSystemInDarkTheme
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.SubscriptionStore
import xyz.wraithnet.phantom.service.PhantomVpnService
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val panelShape = RoundedCornerShape(18.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    refreshVersion: Int = 0,
    externalImport: String? = null,
    onExternalImportConsumed: () -> Unit = {},
    vpnStatus: String = "disconnected",
    vpnError: String = "",
    downloadRate: Long = -1L,
    uploadRate: Long = -1L,
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
    var expandedIds by remember { mutableStateOf(subscriptions.firstOrNull()?.let { setOf(it.id) } ?: emptySet()) }
    var refreshing by remember { mutableStateOf(setOf<String>()) }
    var probing by remember { mutableStateOf(setOf<String>()) }
    var pingCompleted by remember { mutableIntStateOf(0) }
    val latencies = remember { mutableStateMapOf<String, Long>() }
    var showImportMenu by remember { mutableStateOf(false) }
    var activeSubMenu by remember { mutableStateOf<String?>(null) }
    var importMode by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
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
                expandedIds = expandedIds + subscription.id
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
            if (!quiet) {
                softTap()
                snackbar.showSnackbar("Подписка обновлена")
            }
        } catch (e: Exception) {
            if (!quiet) snackbar.showSnackbar(e.message ?: "Не удалось обновить подписку")
        } finally {
            refreshing = refreshing - item.id
        }
    }

    fun probeSubscription(item: Subscription) {
        if (item.id in probing) return
        probing = probing + item.id
        pingCompleted = 0
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
                            pingCompleted++
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

    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        PhantomSettings(
            onBack = { showSettings = false },
            onThemeChange = onThemeChange,
            onBackupRestored = {
                subscriptions = store.read()
                selectedId = prefs.getString("selected_server_id", null)
                selectedSubId = prefs.getString("selected_subscription_id", null)
                pinnedSubId = prefs.getString("pinned_sub_id", null)
                expandedIds = subscriptions.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
                runCatching {
                    PhantomThemeMode.valueOf(prefs.getString("theme", "AUTO") ?: "AUTO")
                }.getOrNull()?.let(onThemeChange)
                showSettings = false
                scope.launch { snackbar.showSnackbar("Резервная копия восстановлена") }
            }
        )
        return
    }

    val standalone = subscriptions.filter { item ->
        !item.source.startsWith("http://", true) &&
            !item.source.startsWith("https://", true) && item.servers.size == 1
    }
    val regular = subscriptions.filterNot { sub -> standalone.any { it.id == sub.id } }
    // One compact card holds every standalone key. A pinned key moves the group first.
    val displayItems: List<Subscription?> = buildList {
        addAll(regular)
        if (standalone.isNotEmpty()) add(null)
    }.sortedByDescending { item ->
        if (item == null) standalone.any { it.id == pinnedSubId }
        else item.id == pinnedSubId
    }
    var showErrorDetails by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "Настройки", Modifier.size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("Phantom VPN", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 16.dp))
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { softTap(); add(pasteText()) },
                        enabled = !importBusy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Вставить")
                    }
                    OutlinedButton(
                        onClick = { softTap(); showImportMenu = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить")
                    }
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            LazyColumn(
                Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "connection-metrics") {
                val isConnected = vpnStatus == "connected"
                val left = if (isConnected) TrafficRates.labelBits(uploadRate) else "0 Кбит/с"
                val right = if (isConnected) TrafficRates.labelBits(downloadRate) else "0 Кбит/с"
                val timer = String.format(Locale.ROOT, "%02d:%02d:%02d",
                    connectedSeconds / 3600L, (connectedSeconds / 60L) % 60L,
                    connectedSeconds % 60L)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text("↑ " + left, color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(timer, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Text("↓ " + right, color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val connected = vpnStatus == "connected"
                    Box(
                        Modifier.size(144.dp)
                            .border(1.5.dp, if (connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline, CircleShape)
                            .padding(9.dp).clip(CircleShape)
                            .background(Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.surfaceVariant)))
                            .clickable(enabled = vpnStatus != "starting") {
                                when {
                                    connected -> { softTap(); onStopVpn() }
                                    selected == null -> scope.launch { snackbar.showSnackbar("Сначала добавьте подписку") }
                                    else -> { softTap(); onStartVpn(selected.raw) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, "Подключить или отключить",
                            Modifier.size(56.dp),
                            tint = if (connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(when (vpnStatus) {
                        "connected" -> "Подключено"
                        "starting" -> "Подключение…"
                        "error" -> "Соединение не установлено"
                        else -> "Отключено"
                    }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (vpnStatus == "error") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onBackground)
                    if (vpnStatus == "error" && vpnError.isNotBlank()) {
                        TextButton(onClick = { showErrorDetails = true }) {
                            Text("Причина ошибки", fontSize = 12.sp)
                        }
                    } else if (selected != null) {
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(CountryFlags.flag(selected), fontSize = 19.sp)
                            Spacer(Modifier.width(7.dp))
                            Text(displayServerName(selected), fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            if (subscriptions.isEmpty()) {
                item {
                    Card(shape = panelShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Подписок пока нет", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(7.dp))
                            Text("Ссылка подписки или отдельный ключ VPN",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))

                        }
                    }
                }
            }
            items(displayItems, key = { it?.id ?: "__standalone_keys__" }) { item ->
                if (item == null) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.5.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = .72f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column {
                            Text(
                                "Отдельные ключи",
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            standalone.sortedByDescending { it.id == pinnedSubId }
                                .forEach { single ->
                                    val server = single.servers.single()
                                    val chosen = selectedMatch?.let { (owner, profile) ->
                                        owner.id == single.id && profile.id == server.id
                                    } == true
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = .16f))
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .background(if (chosen)
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)
                                                else Color.Transparent)
                                            .heightIn(min = 65.dp)
                                            .padding(start = 14.dp, end = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            Modifier.weight(1f).clickable {
                                                softTap()
                                                selectedId = server.id
                                                selectedSubId = single.id
                                                prefs.edit().putString("selected_server_id", server.id)
                                                    .putString("selected_subscription_id", single.id).apply()
                                                if (vpnStatus == "connected") onStartVpn(server.raw)
                                            }.padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(CountryFlags.flag(server), fontSize = 23.sp)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(displayServerName(server), fontSize = 15.sp,
                                                        maxLines = 1,
                                                        fontWeight = if (chosen)
                                                            FontWeight.SemiBold else FontWeight.Medium,
                                                        modifier = Modifier.weight(1f))
                                                    if (pinnedSubId == single.id) {
                                                        Icon(Icons.Default.PushPin, "Закреплено",
                                                            Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                                Text(protocolLabel(server), fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            latencies[pingKey(single, server)]?.takeIf { it > 0 }?.let { delayMs ->
                                                Text("${delayMs} мс", fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            if (chosen) Icon(Icons.Default.CheckCircle, "Выбран",
                                                Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(
                                            onClick = { activeSubMenu = single.id },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(Icons.Default.MoreVert, "Меню ключа",
                                                Modifier.size(21.dp))
                                        }
                                    }
                                }
                        }
                    }
                } else {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.5.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = .72f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = { expandedIds = if (item.id in expandedIds) expandedIds - item.id else expandedIds + item.id },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(if (item.id in expandedIds) Icons.Default.ExpandLess
                                    else Icons.Default.ExpandMore, "Свернуть или развернуть",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f).padding(start = 5.dp).clickable {
                                expandedIds = if (item.id in expandedIds) expandedIds - item.id else expandedIds + item.id
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        modifier = Modifier.weight(1f))
                                    if (pinnedSubId == item.id) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Default.PushPin, "Закреплено сверху",
                                            Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text("Обновлено " + timeLabel(item.updatedAt),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (item.source.startsWith("https://", ignoreCase = true) ||
                                item.source.startsWith("http://", ignoreCase = true)) {
                                IconButton(onClick = { scope.launch { refresh(item) } },
                                    enabled = item.id !in refreshing, modifier = Modifier.size(40.dp)) {
                                    if (item.id in refreshing) CircularProgressIndicator(
                                        Modifier.size(17.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, "Обновить подписку", Modifier.size(21.dp))
                                }
                            }
                            IconButton(onClick = { probeSubscription(item) },
                                enabled = item.id !in probing, modifier = Modifier.size(40.dp)) {
                                if (item.id in probing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Speed, "Проверить пинг", Modifier.size(21.dp))
                            }
                            IconButton(onClick = { activeSubMenu = item.id }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.MoreVert, "Меню подписки", Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (item.totalBytes != null || item.expiresAt != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                item.totalBytes?.let { total ->
                                    val used = (item.uploadBytes ?: 0L) + (item.downloadBytes ?: 0L)
                                    val limit = if (total > 0) sizeLabel(total) else "∞"
                                    Text(sizeLabel(used) + " / " + limit,
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                item.expiresAt?.let {
                                    Text("До " + dateLabel(it), fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val total = item.totalBytes
                            if (total != null && total > 0L) {
                                val used = (item.uploadBytes ?: 0L) + (item.downloadBytes ?: 0L)
                                LinearProgressIndicator(
                                    progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                        .height(3.dp).clip(CircleShape)
                                )
                            }
                        }
                        if (item.id in expandedIds) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                            item.servers.forEach { server ->
                                val supported = ProtocolSupport.isSupported(server.scheme)
                                val singleDirect = !item.source.startsWith("http", ignoreCase = true) &&
                                    item.servers.size == 1
                                val chosen = selectedMatch?.let { (owner, profile) ->
                                    owner.id == item.id && profile.id == server.id
                                } == true
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (chosen)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)
                                            else Color.Transparent)
                                        .clickable {
                                            if (supported) {
                                                softTap()
                                                selectedId = server.id
                                                selectedSubId = item.id
                                                prefs.edit().putString("selected_server_id", server.id)
                                                    .putString("selected_subscription_id", item.id).apply()
                                                if (vpnStatus == "connected") onStartVpn(server.raw)
                                            } else {
                                                scope.launch { snackbar.showSnackbar("Этот протокол пока не поддерживается") }
                                            }
                                        }
                                        .heightIn(min = 62.dp)
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(CountryFlags.flag(server), fontSize = 23.sp)
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(displayServerName(server), fontSize = 15.sp,
                                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1)
                                        Spacer(Modifier.height(3.dp))
                                        Text(protocolLabel(server), fontSize = 10.sp,
                                            maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    latencies[pingKey(item, server)]?.let { ms ->
                                        Text(if (ms >= 0) ms.toString() + " мс" else "—",
                                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(7.dp))
                                    }
                                    if (!singleDirect) {
                                        if (chosen) Icon(Icons.Default.CheckCircle, "Выбран",
                                            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        else if (supported) Box(Modifier.size(16.dp)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                    }

                                }
                                HorizontalDivider(Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = .15f))
                            }
                        }
                    }
                }
                }
            }


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
    if (showOtaSheet && availableUpdate != null && !showSettings &&
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
                deleteId = null
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } }
        )
    }
}

private fun displayServerName(server: ServerProfile): String =
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

private fun protocolLabel(server: ServerProfile): String {
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

private fun sizeLabel(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[i])
}

private fun timeLabel(millis: Long): String =
    SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault()).format(Date(millis))

private fun dateLabel(seconds: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(seconds * 1000L))

@Composable
private fun PhantomSettings(
    onBack: () -> Unit,
    onThemeChange: (PhantomThemeMode) -> Unit,
    onBackupRestored: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(runCatching {
            PhantomThemeMode.valueOf(prefs.getString("theme", "AUTO") ?: "AUTO")
        }.getOrDefault(PhantomThemeMode.AUTO))
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Назад", Modifier.size(24.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Настройки", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Text("ОФОРМЛЕНИЕ", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val previewColors = themeScheme(mode, isSystemInDarkTheme())
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showThemeGallery = true }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(previewColors.background, previewColors.surface,
                                previewColors.primary).forEach { color ->
                                Box(Modifier.size(19.dp).clip(CircleShape)
                                    .background(color).border(1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = .35f), CircleShape))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Тема оформления", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(mode.title, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("ПОДКЛЮЧЕНИЕ", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            PhantomQuickTileService.offerToAdd(context) { tileMessage = it }
                        }.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Кнопка в шторке Android", fontSize = 15.sp)
                            Text("Подключать и отключать VPN из быстрых настроек",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    tileMessage?.let { text ->
                        Text(text,
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Card(shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().clickable { showApps = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Какие приложения используют VPN", fontSize = 15.sp)
                            Text("Выбрать, кому нужен VPN",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Card(shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                    Row(
                        Modifier.fillMaxWidth().clickable { showDns = true }
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("DNS", fontSize = 15.sp)
                            Text("Стандартный, Cloudflare, Google или свой",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                    Row(Modifier.fillMaxWidth().clickable {
                        runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    }.heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Всегда включённый VPN", fontSize = 15.sp)
                            Text("Самый надёжный способ сохранять VPN после перезагрузки",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                    Row(Modifier.fillMaxWidth().clickable {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }.heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ограничения батареи", fontSize = 15.sp)
                            Text("Разрешения фоновой работы",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("ДАННЫЕ И ПЕРЕНОС", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showBackup = true }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Резервная копия", fontSize = 15.sp,
                                fontWeight = FontWeight.Medium)
                            Text("Все подписки, настройки и приложения VPN",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("ПРИЛОЖЕНИЕ", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Card(shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Phantom", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            Text("Xray core " + BuildConfig.XRAY_CORE_VERSION + " · arm64-v8a", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp)
                        }
                        Text(version, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                    Row(Modifier.fillMaxWidth().clickable { showComponents = true }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Компоненты и лицензии", fontSize = 15.sp)
                            Text("Протоколы, библиотеки, открытые лицензии",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("ДИАГНОСТИКА", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Card(shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().clickable { showLog = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Журнал VPN", fontSize = 15.sp)
                            Text("Последние подключения и причины ошибок",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("ОБНОВЛЕНИЯ", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    PhantomUpdateSection()
                }
            }
        }
    }
}
