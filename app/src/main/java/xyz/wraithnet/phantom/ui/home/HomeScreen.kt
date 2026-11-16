package xyz.wraithnet.phantom.ui.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionFetcher
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.SubscriptionStore
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode

@Composable
fun HomeScreen(onThemeChange: (PhantomThemeMode) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { SubscriptionStore(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var subscriptions by remember { mutableStateOf(store.read()) }
    var selected by remember { mutableStateOf<ServerProfile?>(subscriptions.firstOrNull()?.servers?.firstOrNull()) }
    var addDialog by remember { mutableStateOf(false) }
    var serverDialog by remember { mutableStateOf(false) }
    var menuDialog by remember { mutableStateOf(false) }
    var settingsScreen by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun pasteText(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    fun addSubscription(raw: String, title: String? = null) {
        if (raw.isBlank()) {
            scope.launch { snackbar.showSnackbar("Буфер обмена пуст") }
            return
        }
        loading = true
        scope.launch {
            runCatching {
                val downloaded = if (raw.trimStart().startsWith("http://") || raw.trimStart().startsWith("https://")) {
                    SubscriptionFetcher.download(raw.trim(), store.deviceId())
                } else null
                val parsed = SubscriptionParser.parse(downloaded?.body ?: raw, title ?: downloaded?.title).copy(
                    updateIntervalSeconds = downloaded?.updateIntervalSeconds ?: 3600,
                    uploadBytes = downloaded?.uploadBytes,
                    downloadBytes = downloaded?.downloadBytes,
                    totalBytes = downloaded?.totalBytes,
                    expiresAt = downloaded?.expiresAt,
                )
                subscriptions = subscriptions + parsed
                store.write(subscriptions)
                selected = selected ?: parsed.servers.firstOrNull()
                addDialog = false
                menuDialog = false
                snackbar.showSnackbar("Подписка «${parsed.title}» добавлена")
            }.onFailure { snackbar.showSnackbar(it.message ?: "Не удалось загрузить подписку") }
            loading = false
        }
    }

    if (settingsScreen) {
        SettingsScreen(onBack = { settingsScreen = false }, onThemeChange = onThemeChange)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { settingsScreen = true }) { Icon(Icons.Default.Settings, "Настройки", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.width(48.dp))
                    IconButton(onClick = { menuDialog = true }) { Icon(Icons.Default.Add, "Добавить", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(32.dp)) }
                }
                Spacer(Modifier.height(28.dp))
                PowerButton(connected, loading) {
                    if (selected == null) scope.launch { snackbar.showSnackbar("Сначала добавьте подписку") }
                    else {
                        connected = !connected
                        scope.launch { snackbar.showSnackbar(if (connected) "Подготовка подключения…" else "VPN отключён") }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(if (loading) "Загрузка подписки…" else if (connected) "Подключение…" else "Отключено", color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                ServerSelector(selected) { if (subscriptions.any { it.servers.isNotEmpty() }) serverDialog = true }
                Spacer(Modifier.height(16.dp))
                if (subscriptions.isEmpty()) {
                    EmptyState(Modifier.weight(1f), { addSubscription(pasteText()) }, { addDialog = true })
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(subscriptions, key = { it.id }) { item ->
                            SubscriptionCard(item, { scope.launch { snackbar.showSnackbar("Обновление подписки…") } }) { selected = item.servers.firstOrNull(); serverDialog = true }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    ActionButton(Icons.Default.ContentPaste, "Из буфера", Modifier.weight(1f)) { addSubscription(pasteText()) }
                    ActionButton(Icons.Default.QrCodeScanner, "QR-код", Modifier.weight(1f)) { scope.launch { snackbar.showSnackbar("QR-сканер добавим следующим блоком") } }
                }
            }
        }
    }
    if (menuDialog) AddMenu({ addSubscription(pasteText()) }, { menuDialog = false; addDialog = true }, { menuDialog = false; scope.launch { snackbar.showSnackbar("QR-сканер добавим следующим блоком") } }) { menuDialog = false }
    if (addDialog) ManualAddDialog(::addSubscription) { addDialog = false }
    if (serverDialog) ServerDialog(subscriptions.flatMap { it.servers }, selected, { selected = it; serverDialog = false }) { serverDialog = false }
}

@Composable
private fun PowerButton(connected: Boolean, loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(230.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(.35f), CircleShape).padding(14.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(.18f), CircleShape).padding(18.dp).clip(CircleShape).background(if (connected) MaterialTheme.colorScheme.primary.copy(.3f) else MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = !loading, onClick = onClick), contentAlignment = Alignment.Center) {
            if (loading) Text("…", color = MaterialTheme.colorScheme.primary, fontSize = 52.sp) else Icon(Icons.Default.PowerSettingsNew, "Подключиться", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(78.dp))
        }
    }
}

@Composable
private fun ServerSelector(server: ServerProfile?, onClick: () -> Unit) {
    Card(onClick = onClick, Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(server?.countryFlag?.ifBlank { "◇" } ?: "◇", fontSize = 24.sp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(server?.name ?: "Сервер не выбран", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium); Text(server?.scheme?.uppercase() ?: "Добавьте подписку", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            Icon(Icons.Default.ExpandMore, "Выбрать сервер", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onPaste: () -> Unit, onAdd: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Подписок пока нет", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp)); Text("Добавьте ссылку подписки или сервер вручную", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPaste, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.primary)) { Text("Вставить", color = MaterialTheme.colorScheme.onPrimary) }
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surface)) { Text("Добавить") }
        }
    }
}

@Composable
private fun SubscriptionCard(item: Subscription, onRefresh: () -> Unit, onSelect: () -> Unit) {
    Card(onClick = onSelect, Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(item.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, Modifier.weight(1f)); IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить", tint = MaterialTheme.colorScheme.onSurfaceVariant) } }; Text("${item.servers.size} серверов · автообновление 1 ч", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick, modifier.height(58.dp), RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surface)) { Icon(icon, label); Spacer(Modifier.width(8.dp)); Text(label) }
}

@Composable
private fun AddMenu(onPaste: () -> Unit, onManual: () -> Unit, onQr: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, title = { Text("Добавить подписку") }, text = { Column { MenuRow("Вставить из буфера", Icons.Default.ContentPaste, onPaste); MenuRow("QR-код", Icons.Default.QrCodeScanner, onQr); MenuRow("Ручной ввод", Icons.Default.Add, onManual) } }, confirmButton = { TextButton(onDismiss) { Text("Отмена") } })
}

@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, label, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Text(label) } }

@Composable
private fun ManualAddDialog(onAdd: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }; var title by remember { mutableStateOf("") }
    AlertDialog(onDismiss, title = { Text("Добавить подписку") }, text = { Column { OutlinedTextField(value, { value = it }, label = { Text("Ссылка или конфигурация") }, minLines = 3); Spacer(Modifier.height(8.dp)); OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true) } }, confirmButton = { Button({ onAdd(value, title.takeIf(String::isNotBlank)) }, enabled = value.isNotBlank()) { Text("Добавить") } }, dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

@Composable
private fun ServerDialog(servers: List<ServerProfile>, selected: ServerProfile?, onSelect: (ServerProfile) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, title = { Text("Выберите сервер") }, text = { LazyColumn { items(servers, key = { it.id }) { server -> Row(Modifier.fillMaxWidth().clickable { onSelect(server) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(server.countryFlag.ifBlank { "◇" }, fontSize = 22.sp); Spacer(Modifier.width(10.dp)); Column { Text(server.name, fontWeight = if (server.id == selected?.id) FontWeight.Bold else FontWeight.Normal); Text(server.scheme.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) } } } } }, confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onThemeChange: (PhantomThemeMode) -> Unit) {
    var autoConnect by remember { mutableStateOf(false) }; var perApp by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Назад") }; Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SectionTitle("Интерфейс") }
                item { SettingRow("Тема оформления", "Графит по умолчанию") { onThemeChange(PhantomThemeMode.GRAPHITE) } }
                item { SettingRow("Ночная тема", "Сине-чёрная палитра") { onThemeChange(PhantomThemeMode.NIGHT) } }
                item { SectionTitle("Туннель") }
                item { SettingRow("Маршрутизация", "Все приложения через VPN") {} }
                item { SwitchRow("Прокси для выбранных приложений", perApp) { perApp = it } }
                item { SettingRow("Предпочтительный тип IP", "Автоматически") {} }
                item { SectionTitle("Расширенные") }
                item { SwitchRow("Автозапуск приложения", autoConnect) { autoConnect = it } }
                item { SettingRow("Подписки", "Обновление и заголовки Remnawave") {} }
                item { SettingRow("Пинг серверов", "Проверка доступности") {} }
                item { SectionTitle("Информация") }
                item { SettingRow("FAQ", "Помощь по подключению") {} }
                item { SettingRow("О приложении", "Версия 0.1.0") {} }
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
@Composable private fun SettingRow(title: String, summary: String, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }; Icon(Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(checked, onCheckedChange) } }
