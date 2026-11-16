package xyz.wraithnet.phantom.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionFetcher
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.SubscriptionStore
import xyz.wraithnet.phantom.service.PhantomVpnService
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val panelShape = RoundedCornerShape(18.dp)

@Composable
fun HomeScreen(
    refreshVersion: Int = 0,
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
    var showAdd by remember { mutableStateOf(false) }
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
        if (raw.isBlank()) {
            scope.launch { snackbar.showSnackbar("Вставьте ссылку подписки") }
            return
        }
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
                showAdd = false
                snackbar.showSnackbar("Подписка добавлена: " + subscription.servers.size + " серверов")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Ошибка добавления подписки")
            }
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

    if (showSettings) {
        PhantomSettings(onBack = { showSettings = false }, onThemeChange = onThemeChange)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton({ showSettings = true }) {
                    Icon(Icons.Default.Settings, "Настройки", Modifier.size(26.dp))
                }
                Text("Phantom", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton({ showAdd = true }) {
                    Icon(Icons.Default.Add, "Добавить подписку", Modifier.size(28.dp))
                }
            }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(154.dp)
                            .border(2.dp, if (vpnStatus == "connected") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline, CircleShape)
                            .padding(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = vpnStatus != "starting") {
                                if (vpnStatus == "connected") onStopVpn()
                                else if (selected == null) scope.launch { snackbar.showSnackbar("Сначала добавьте подписку") }
                                else onStartVpn(selected.raw)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew, "Подключение",
                            Modifier.size(58.dp),
                            tint = if (vpnStatus == "connected") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        when (vpnStatus) {
                            "connected" -> "Подключено"
                            "starting" -> "Проверка подключения…"
                            "error" -> "Не удалось подключиться"
                            else -> "Отключено"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (vpnStatus == "error" && vpnError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(vpnError, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontSize = 13.sp)
                    } else if (selected != null) {
                        Spacer(Modifier.height(5.dp))
                        Text(selected.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            if (subscriptions.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = panelShape) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Добавьте подписку", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Ссылка Remnawave или профиль сервера", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { add(pasteText()) }) {
                                    Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(7.dp)); Text("Из буфера")
                                }
                                Button(onClick = { showAdd = true }) { Text("Добавить") }
                            }
                        }
                    }
                }
            }
            items(subscriptions, key = { it.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = panelShape) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ expanded = if (expanded == item.id) null else item.id }, Modifier.size(34.dp)) {
                                Icon(if (expanded == item.id) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Свернуть/развернуть")
                            }
                            Column(Modifier.weight(1f).padding(start = 6.dp).clickable {
                                expanded = if (expanded == item.id) null else item.id
                            }) {
                                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(item.servers.size.toString() + " серверов · " + timeLabel(item.updatedAt),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            IconButton(onClick = { scope.launch { refresh(item) } }, enabled = item.id !in refreshing) {
                                if (item.id in refreshing) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, "Обновить подписку")
                                }
                            }
                            IconButton(onClick = { deleteId = item.id }) {
                                Icon(Icons.Default.DeleteOutline, "Удалить подписку", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (item.totalBytes != null || item.expiresAt != null) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
                                if (item.totalBytes != null) {
                                    val used = (item.uploadBytes ?: 0L) + (item.downloadBytes ?: 0L)
                                    Text("Трафик: " + sizeLabel(used) + " / " + sizeLabel(item.totalBytes),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (item.totalBytes > 0L) {
                                        Spacer(Modifier.height(5.dp))
                                        LinearProgressIndicator(
                                            progress = { (used.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                                        )
                                    }
                                }
                                item.expiresAt?.let {
                                    Text("Действует до " + dateLabel(it),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (expanded == item.id) {
                            HorizontalDivider(Modifier.padding(top = 7.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            item.servers.forEach { server ->
                                val supported = server.scheme.lowercase() in setOf("vless", "vmess", "trojan", "ss", "xray", "hysteria", "hysteria2", "hy2")
                                val chosen = selected?.id == server.id
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (chosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
                                            else Color.Transparent)
                                        .clickable {
                                            if (supported) {
                                                selectedId = server.id
                                                prefs.edit().putString("selected_server_id", server.id).apply()
                                                if (vpnStatus == "connected") onStartVpn(server.raw)
                                            } else {
                                                scope.launch { snackbar.showSnackbar(
                                                    server.scheme.uppercase() + ": для подключения требуется дополнительное ядро"
                                                ) }
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(server.countryFlag.ifBlank { "◇" }, fontSize = 23.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(server.name, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
                                        Text(protocolLabel(server),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                    if (chosen) Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                                    else if (!supported) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider(Modifier.padding(start = 50.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = .15f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var value by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Добавить подписку") },
            text = {
                Column {
                    OutlinedTextField(value, { value = it }, label = { Text("URL или конфигурация") }, minLines = 3)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(title, { title = it }, label = { Text("Название (необязательно)") })
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { value = pasteText() }) {
                        Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Вставить из буфера")
                    }
                }
            },
            confirmButton = { Button(onClick = { add(value, title.takeIf { it.isNotBlank() }) }, enabled = value.isNotBlank()) { Text("Добавить") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
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

private fun protocolLabel(server: ServerProfile): String = when (server.scheme.lowercase()) {
    "xray" -> "Xray JSON"
    "hysteria", "hysteria2", "hy2" -> "Hysteria2"
    else -> server.scheme.uppercase()
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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Оформление", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Card(shape = panelShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    listOf(PhantomThemeMode.GRAPHITE, PhantomThemeMode.NIGHT).forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable {
                            mode = m
                            onThemeChange(m)
                        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.title, Modifier.weight(1f))
                            RadioButton(selected = mode == m, onClick = { mode = m; onThemeChange(m) })
                        }
                    }
                }
            }
            item {
                Text("Стабильность подключения", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Card(shape = panelShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Держать VPN активным")
                            Spacer(Modifier.height(4.dp))
                            Text("Не давать процессору засыпать при подключении. Увеличивает расход батареи.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(checked = keepActive, onCheckedChange = { next ->
                            keepActive = next
                            prefs.edit().putBoolean("keep_active", next).apply()
                            if (prefs.getBoolean("vpn_running", false)) {
                                context.startService(Intent(context, PhantomVpnService::class.java)
                                    .setAction(PhantomVpnService.ACTION_KEEP_ALIVE))
                            }
                        })
                    }
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                    }.padding(16.dp)) {
                        Text("Всегда включённый VPN")
                        Spacer(Modifier.height(4.dp))
                        Text("Открыть настройки Android, чтобы включить системное Always-on VPN",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }.padding(16.dp)) {
                        Text("Ограничения батареи")
                        Spacer(Modifier.height(4.dp))
                        Text("Проверьте, не ограничивает ли Android работу Phantom в фоне",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            item {
                Text("О приложении", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Card(shape = panelShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Phantom 0.2.0", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("Xray • Android", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}