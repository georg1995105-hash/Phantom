package xyz.wraithnet.phantom.ui.home

import android.content.ClipboardManager
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
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import xyz.wraithnet.phantom.data.ConfigurationFiles
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
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
import xyz.wraithnet.phantom.data.TrafficRates
import xyz.wraithnet.phantom.data.PhantomUpdates
import xyz.wraithnet.phantom.data.PhantomRelease
import xyz.wraithnet.phantom.data.CountryFlags
import xyz.wraithnet.phantom.data.FragmentationOptions
import java.io.File
import xyz.wraithnet.phantom.ui.update.PhantomUpdateSection
import xyz.wraithnet.phantom.ui.settings.AppRoutingScreen
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
    LaunchedEffect(snackbar.currentSnackbarData) {
        if (snackbar.currentSnackbarData != null) {
            delay(1650L)
            snackbar.currentSnackbarData?.dismiss()
        }
    }
    val scope = rememberCoroutineScope()
    var subscriptions by remember { mutableStateOf(store.read()) }
    var selectedId by remember { mutableStateOf(prefs.getString("selected_server_id", null)) }
    val selected = subscriptions.flatMap { it.servers }.firstOrNull { it.id == selectedId }
        ?: subscriptions.firstOrNull()?.servers?.firstOrNull()
    var expandedIds by remember { mutableStateOf(subscriptions.firstOrNull()?.let { setOf(it.id) } ?: emptySet()) }
    var refreshing by remember { mutableStateOf(setOf<String>()) }
    var probing by remember { mutableStateOf(setOf<String>()) }
    var pingCompleted by remember { mutableIntStateOf(0) }
    val latencies = remember { mutableStateMapOf<String, Long>() }
    var showImportMenu by remember { mutableStateOf(false) }
    var activeSubMenu by remember { mutableStateOf<String?>(null) }
    var pinnedSubId by remember { mutableStateOf(prefs.getString("pinned_sub_id", null)) }
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
                val downloaded = if (remote) SubscriptionFetcher.download(source, store.deviceId()) else null
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
                if (selectedId == null) selectedId = subscription.servers.firstOrNull()?.id
                importMode = null
                showImportMenu = false
                snackbar.showSnackbar("Добавлено серверов: " + subscription.servers.size)
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

    var exportWarning by remember { mutableStateOf<ServerProfile?>(null) }
    var exportProfile by remember { mutableStateOf<ServerProfile?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val profile = exportProfile
        exportProfile = null
        if (uri != null && profile != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(profile.raw.toByteArray(Charsets.UTF_8))
                        } ?: error("Не удалось сохранить файл")
                    }
                    snackbar.showSnackbar("Конфигурация сохранена")
                } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: "Ошибка экспорта")
                }
            }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            InputStreamReader(it, Charsets.UTF_8).use { reader ->
                                ConfigurationFiles.read(reader)
                            }
                        } ?: error("Не удалось открыть файл")
                    }
                    add(raw)
                } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: "Не удалось импортировать файл")
                }
            }
        }
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
            val remote = SubscriptionFetcher.download(item.source, store.deviceId())
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
            if (!quiet) snackbar.showSnackbar("Обновлено: " + updated.servers.size + " серверов")
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
                val supported = setOf("vless", "vmess", "trojan", "ss", "xray", "hysteria", "hysteria2", "hy2")
                val servers = item.servers.filter { it.scheme.lowercase() in supported }
                coroutineScope {
                    val limit = Semaphore(3)
                    servers.map { server ->
                        async {
                            val ping = limit.withPermit {
                                runCatching { LatencyProbe.measure(context.applicationContext, server) }
                                    .getOrNull() ?: -1L
                            }
                            latencies[server.id] = ping
                            pingCompleted++
                            server.id to ping
                        }
                    }.awaitAll().forEach { (serverId, delayMs) ->
                        latencies[serverId] = delayMs
                    }
                }
                val checked = servers.count { (latencies[it.id] ?: -1L) > 0L }
                snackbar.showSnackbar(
                    if (checked == 0) "Не получено ни одного результата проверки"
                    else "Проверено: " + checked + " из " + servers.size + " серверов"
                )
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
        PhantomSettings(onBack = { showSettings = false }, onThemeChange = onThemeChange)
        return
    }

    var showErrorDetails by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "Настройки", Modifier.size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showImportMenu = true }) {
                    Icon(Icons.Default.Add, "Добавить конфигурацию", Modifier.size(27.dp))
                }
            }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 3.dp),
                    snackbar = { data ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = .4f)),
                            shadowElevation = 2.dp
                        ) {
                            Text(data.visuals.message,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                fontSize = 13.sp,
                                maxLines = 2,
                                textAlign = TextAlign.Center)
                        }
                    }
                )
            }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
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
                                    connected -> onStopVpn()
                                    selected == null -> scope.launch { snackbar.showSnackbar("Сначала добавьте подписку") }
                                    else -> onStartVpn(selected.raw)
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { add(pasteText()) }) {
                                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp)); Text("Вставить")
                                }
                                Button(onClick = { importMode = "url" }) { Text("Добавить") }
                            }
                        }
                    }
                }
            }
            items(subscriptions.sortedByDescending { it.id == pinnedSubId }, key = { it.id }) { item ->
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
                                Text(item.title, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("Обновлено " + timeLabel(item.updatedAt),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scope.launch { refresh(item) } },
                                enabled = item.id !in refreshing, modifier = Modifier.size(40.dp)) {
                                if (item.id in refreshing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, "Обновить подписку", Modifier.size(21.dp))
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
                                val supported = server.scheme.lowercase() in setOf(
                                    "vless", "vmess", "trojan", "ss", "xray",
                                    "hysteria", "hysteria2", "hy2"
                                )
                                val chosen = selected?.id == server.id
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (chosen)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)
                                            else Color.Transparent)
                                        .clickable {
                                            if (supported) {
                                                selectedId = server.id
                                                prefs.edit().putString("selected_server_id", server.id).apply()
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
                                    latencies[server.id]?.let { ms ->
                                        Text(if (ms >= 0) ms.toString() + " мс" else "—",
                                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(7.dp))
                                    }
                                    if (chosen) Icon(Icons.Default.CheckCircle, "Выбран", Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    else if (supported) Box(Modifier.size(16.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                }
                                HorizontalDivider(Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = .15f))
                            }
                        }
                    }
                }
            }
            if (availableUpdate != null && !otaDismissed) {
                item(key = "ota-update") {
                    val release = availableUpdate!!
                    Card(
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = .38f)),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Обновление " + release.version,
                                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Новая версия доступна",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { otaDismissed = true },
                                    enabled = !otaDownload, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Скрыть", Modifier.size(18.dp))
                                }
                            }
                            val details = formatUpdateNotes(release.notes)
                            if (details.isNotBlank()) {
                                Spacer(Modifier.height(7.dp))
                                Text(details, fontSize = 11.sp, lineHeight = 15.sp,
                                    maxLines = 4,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (otaDownload) {
                                Spacer(Modifier.height(7.dp))
                                LinearProgressIndicator(
                                    progress = { otaProgress.toFloat() / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("Загрузка " + otaProgress + "%", fontSize = 11.sp)
                            }
                            otaError?.let { message ->
                                Text(message, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(7.dp))
                            OutlinedButton(onClick = ::startOtaInstall,
                                enabled = !otaDownload,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)) {
                                Text(if (otaFile?.exists() == true) "Установить"
                                    else "Скачать обновление", fontSize = 13.sp)
                            }
                        }
                    }
                }
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
                funActionRow("Импортировать файл", Icons.Default.FileOpen) {
                    showImportMenu = false
                    importFileLauncher.launch(arrayOf("text/plain", "application/json", "*/*"))
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

    exportWarning?.let { profile ->
        AlertDialog(
            onDismissRequest = { exportWarning = null },
            title = { Text("Экспорт конфигурации") },
            text = { Text("Файл содержит приватные параметры подключения. " +
                "Сохраняйте его только в надёжном месте и не отправляйте посторонним.") },
            confirmButton = {
                TextButton(onClick = {
                    exportWarning = null
                    exportProfile = profile
                    exportLauncher.launch("phantom-selected.txt")
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { exportWarning = null }) { Text("Отмена") }
            }
        )
    }

    if (showErrorDetails && vpnError.isNotBlank()) {
        AlertDialog(
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
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                    funActionRow("Импортировать конфигурацию", Icons.Default.FileOpen) {
                        activeSubMenu = null
                        importFileLauncher.launch(arrayOf("text/plain", "application/json", "*/*"))
                    }
                    funActionRow("Экспортировать выбранный сервер", Icons.Default.SaveAlt) {
                        activeSubMenu = null
                        val chosen = selected?.takeIf { candidate ->
                            sub.servers.any { it.id == candidate.id }
                        }
                        if (chosen != null) {
                            exportWarning = chosen
                        } else {
                            scope.launch {
                                snackbar.showSnackbar("Выберите сервер этой подписки")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                    funActionRow("Удалить подписку", Icons.Default.DeleteOutline) {
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
                    if (manual) "Поддерживаются VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и Xray JSON."
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
            onDismissRequest = { deleteId = null },
            title = { Text("Удалить подписку?") },
            text = { Text("Серверы этой подписки будут удалены с устройства.") },
            confirmButton = { TextButton(onClick = {
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

private fun formatUpdateNotes(notes: String): String =
    notes.lineSequence()
        .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .take(4)
        .joinToString("\n") { "• " + it }

private fun timeLabel(millis: Long): String =
    SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault()).format(Date(millis))

private fun dateLabel(seconds: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(seconds * 1000L))

@Composable
private fun PhantomSettings(onBack: () -> Unit, onThemeChange: (PhantomThemeMode) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(runCatching {
            PhantomThemeMode.valueOf(prefs.getString("theme", "AUTO") ?: "AUTO")
        }.getOrDefault(PhantomThemeMode.AUTO))
    }
    var keepActive by remember { mutableStateOf(prefs.getBoolean("keep_active", false)) }
    var showThemeGallery by remember { mutableStateOf(false) }
    var tileMessage by remember { mutableStateOf<String?>(null) }
    var fragment by remember { mutableStateOf(FragmentationOptions.load(context)) }
    var fragmentMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
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
                    Row(Modifier.fillMaxWidth().clickable {
                        runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    }.heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Всегда включённый VPN", fontSize = 15.sp)
                            Text("Системная функция Android",
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
                    PhantomUpdateSection()
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
        }
    }
}
