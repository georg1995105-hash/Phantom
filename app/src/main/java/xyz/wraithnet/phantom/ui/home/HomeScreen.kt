package xyz.wraithnet.phantom.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Speed
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
    onStartVpn: (String) -> Unit = {},
    onStopVpn: () -> Unit = {},
    onThemeChange: (PhantomThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { SubscriptionStore(context) }
    val prefs = remember { context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var subscriptions by remember { mutableStateOf(store.read()) }
    var selectedId by remember { mutableStateOf(prefs.getString("selected_server_id", null)) }
    val selected = subscriptions.flatMap { it.servers }.firstOrNull { it.id == selectedId }
        ?: subscriptions.firstOrNull()?.servers?.firstOrNull()
    var expanded by remember { mutableStateOf(subscriptions.firstOrNull()?.id) }
    var refreshing by remember { mutableStateOf(setOf<String>()) }
    var probing by remember { mutableStateOf(setOf<String>()) }
    val latencies = remember { mutableStateMapOf<String, Long>() }
    var showImportMenu by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val lastAttempt = remember { mutableMapOf<String, Long>() }

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
        importBusy = true
        importError = null
        scope.launch {
            try {
                val source = raw.trim()
                val remote = source.startsWith("https://") || source.startsWith("http://")
                val downloaded = if (remote) SubscriptionFetcher.download(source, store.deviceId()) else null
                val parsed = SubscriptionParser.parse(downloaded?.body ?: source, customTitle ?: downloaded?.title)
                val subscription = parsed.copy(
                    source = if (remote) source else parsed.source,
                    updateIntervalSeconds = downloaded?.updateIntervalSeconds ?: 3600L,
                    uploadBytes = downloaded?.uploadBytes,
                    downloadBytes = downloaded?.downloadBytes,
                    totalBytes = downloaded?.totalBytes,
                    expiresAt = downloaded?.expiresAt
                )
                persist(subscriptions + subscription)
                expanded = subscription.id
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
            val parsed = SubscriptionParser.parse(remote.body, remote.title ?: item.title)
            val updated = parsed.copy(
                id = item.id,
                source = item.source,
                updatedAt = System.currentTimeMillis(),
                title = remote.title ?: item.title,
                updateIntervalSeconds = remote.updateIntervalSeconds ?: item.updateIntervalSeconds,
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

    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        PhantomSettings(onBack = { showSettings = false }, onThemeChange = onThemeChange)
        return
    }

    var showErrorDetails by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "Настройки", Modifier.size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("Phantom", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { showImportMenu = true }) {
                        Icon(Icons.Default.Add, "Добавить подписку", Modifier.size(27.dp))
                    }
                    DropdownMenu(
                        expanded = showImportMenu,
                        onDismissRequest = { showImportMenu = false },
                        modifier = Modifier.widthIn(min = 258.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Добавить подписку") },
                            leadingIcon = { Icon(Icons.Default.Link, null, Modifier.size(21.dp)) },
                            onClick = { showImportMenu = false; importMode = "url" }
                        )
                        DropdownMenuItem(
                            text = { Text("Вставить из буфера обмена") },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(21.dp)) },
                            onClick = { showImportMenu = false; add(pasteText()) }
                        )
                        DropdownMenuItem(
                            text = { Text("QR-код") },
                            leadingIcon = { Icon(Icons.Default.QrCodeScanner, null, Modifier.size(21.dp)) },
                            onClick = {
                                showImportMenu = false
                                scanLauncher.launch(
                                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Наведите камеру на QR-код")
                                        .setOrientationLocked(false)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Ручной ввод") },
                            leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(21.dp)) },
                            onClick = { showImportMenu = false; importMode = "manual" }
                        )
                    }
                }
            }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
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
                    Spacer(Modifier.height(12.dp))
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
                        Text(displayServerName(selected), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
                            Text("Добавьте ссылку Remnawave или профиль",
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
            items(subscriptions, key = { it.id }) { item ->
                var menuExpanded by remember(item.id) { mutableStateOf(false) }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { expanded = if (expanded == item.id) null else item.id },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(if (expanded == item.id) Icons.Default.ExpandLess
                                    else Icons.Default.ExpandMore, "Свернуть или развернуть",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f).padding(start = 5.dp).clickable {
                                expanded = if (expanded == item.id) null else item.id
                            }) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(item.servers.size.toString() + " серверов · " + timeLabel(item.updatedAt),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { probeSubscription(item) },
                                enabled = item.id !in probing, modifier = Modifier.size(36.dp)) {
                                if (item.id in probing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Speed, "Проверить пинг", Modifier.size(21.dp))
                            }
                            IconButton(onClick = { scope.launch { refresh(item) } },
                                enabled = item.id !in refreshing, modifier = Modifier.size(36.dp)) {
                                if (item.id in refreshing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, "Обновить подписку", Modifier.size(21.dp))
                            }
                            Box {
                                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MoreVert, "Управление подпиской", Modifier.size(21.dp))
                                }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Удалить подписку") },
                                        onClick = { menuExpanded = false; deleteId = item.id }
                                    )
                                }
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
                        if (expanded == item.id) {
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
                                    Text(server.countryFlag.ifBlank { "◇" }, fontSize = 24.sp)
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
        }
    }
    if (showErrorDetails && vpnError.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showErrorDetails = false },
            title = { Text("Ошибка подключения") },
            text = { Text(vpnError, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { showErrorDetails = false }) { Text("Закрыть") } }
        )
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
                    if (manual) "Вставьте ссылку сервера, список ссылок или Xray JSON."
                    else "Укажите ссылку подписки. Серверы и название загрузятся автоматически.",
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
    server.name.trim().removePrefix(server.countryFlag).trim().ifBlank { server.name }

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
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))

private fun dateLabel(seconds: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(seconds * 1000L))

@Composable
private fun PhantomSettings(onBack: () -> Unit, onThemeChange: (PhantomThemeMode) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(runCatching {
            PhantomThemeMode.valueOf(prefs.getString("theme", "GRAPHITE") ?: "GRAPHITE")
        }.getOrDefault(PhantomThemeMode.GRAPHITE))
    }
    var keepActive by remember { mutableStateOf(prefs.getBoolean("keep_active", false)) }
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
            Modifier.fillMaxSize().padding(inner).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Text("ОФОРМЛЕНИЕ", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PhantomThemeMode.GRAPHITE, PhantomThemeMode.NIGHT).forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option; onThemeChange(option) },
                            label = { Text(option.title) },
                            modifier = Modifier.weight(1f),
                            leadingIcon = if (mode == option) {
                                { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }
            item {
                Text("ПОДКЛЮЧЕНИЕ", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
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
                            Text("Android · Xray", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp)
                        }
                        Text(version, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
