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
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.SubscriptionStore

private val Background = Color(0xFF0B0E17)
private val Surface = Color(0xFF151B2A)
private val Accent = Color(0xFF8298FF)
private val Muted = Color(0xFF9BA6C0)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val store = remember { SubscriptionStore(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var subscriptions by remember { mutableStateOf(store.read()) }
    var selected by remember { mutableStateOf<ServerProfile?>(subscriptions.firstOrNull()?.servers?.firstOrNull()) }
    var addDialog by remember { mutableStateOf(false) }
    var serverDialog by remember { mutableStateOf(false) }
    var menuDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }

    fun pasteText(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    fun addSubscription(raw: String, title: String? = null) {
        runCatching {
            val item = SubscriptionParser.parse(raw, title)
            subscriptions = subscriptions + item
            store.write(subscriptions)
            selected = selected ?: item.servers.firstOrNull()
            addDialog = false
            scope.launch { snackbar.showSnackbar("Подписка «${item.title}» добавлена") }
        }.onFailure {
            scope.launch { snackbar.showSnackbar(it.message ?: "Не удалось прочитать подписку") }
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF0B0E17), Color(0xFF171F34))))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { settingsDialog = true }) {
                        Icon(Icons.Default.Settings, "Настройки", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Text("Phantom", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    IconButton(onClick = { menuDialog = true }) {
                        Icon(Icons.Default.Add, "Добавить", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                Spacer(Modifier.height(30.dp))
                PowerButton(connected = connected, onClick = {
                    if (selected == null) {
                        scope.launch { snackbar.showSnackbar("Сначала добавьте подписку") }
                    } else {
                        connected = !connected
                        scope.launch { snackbar.showSnackbar(if (connected) "Подготовка подключения…" else "VPN отключён") }
                    }
                })
                Spacer(Modifier.height(14.dp))
                Text(
                    if (connected) "Подключение…" else "Отключено",
                    color = if (connected) Accent else Muted,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                ServerSelector(selected, onClick = { if (subscriptions.any { it.servers.isNotEmpty() }) serverDialog = true })
                Spacer(Modifier.height(16.dp))

                if (subscriptions.isEmpty()) {
                    EmptyState(onPaste = { addSubscription(pasteText()) }, onAdd = { addDialog = true })
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                        items(subscriptions, key = { it.id }) { subscription ->
                            SubscriptionCard(subscription, onRefresh = {
                                scope.launch { snackbar.showSnackbar("Автообновление будет добавлено на следующем этапе") }
                            }, onSelect = { selected = subscription.servers.firstOrNull(); serverDialog = true })
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    ActionButton(Icons.Default.ContentPaste, "Из буфера", Modifier.weight(1f)) { addSubscription(pasteText()) }
                    ActionButton(Icons.Default.QrCodeScanner, "QR-код", Modifier.weight(1f)) {
                        scope.launch { snackbar.showSnackbar("Сканер QR будет подключён следующим блоком") }
                    }
                }
            }
        }
    }

    if (menuDialog) AddMenu(onPaste = { menuDialog = false; addSubscription(pasteText()) }, onManual = { menuDialog = false; addDialog = true }, onQr = { menuDialog = false; scope.launch { snackbar.showSnackbar("Сканер QR будет подключён следующим блоком") } }, onDismiss = { menuDialog = false })
    if (addDialog) ManualAddDialog(onAdd = ::addSubscription, onDismiss = { addDialog = false })
    if (serverDialog) ServerDialog(subscriptions.flatMap { it.servers }, selected, onSelect = { selected = it; serverDialog = false }, onDismiss = { serverDialog = false })
    if (settingsDialog) SettingsDialog(onDismiss = { settingsDialog = false })
}

@Composable
private fun PowerButton(connected: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(Modifier.size(230.dp).border(1.dp, Accent.copy(alpha = .32f), CircleShape).padding(14.dp).border(1.dp, Accent.copy(alpha = .18f), CircleShape).padding(18.dp).clip(CircleShape).background(if (connected) Accent.copy(.32f) else Color(0xFF19223A)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PowerSettingsNew, "Подключиться", tint = Accent, modifier = Modifier.size(78.dp))
        }
    }
}

@Composable
private fun ServerSelector(server: ServerProfile?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(server?.countryFlag?.ifBlank { "◇" } ?: "◇", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server?.name ?: "Сервер не выбран", color = Color.White, fontWeight = FontWeight.Medium)
                Text(server?.scheme?.uppercase() ?: "Добавьте подписку", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Default.ExpandMore, "Выбрать сервер", tint = Muted)
        }
    }
}

@Composable
private fun EmptyState(onPaste: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Подписок пока нет", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Добавьте ссылку подписки или сервер вручную", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPaste, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Вставить") }
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Surface)) { Text("Добавить") }
        }
    }
}

@Composable
private fun SubscriptionCard(item: Subscription, onRefresh: () -> Unit, onSelect: () -> Unit) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить", tint = Muted) }
            }
            Text("${item.servers.size} серверов · обновлено сейчас", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Surface)) {
        Icon(icon, label, tint = Color.White); Spacer(Modifier.width(8.dp)); Text(label, color = Color.White)
    }
}

@Composable
private fun AddMenu(onPaste: () -> Unit, onManual: () -> Unit, onQr: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить сервер") }, text = { Column {
        MenuRow("Вставить из буфера", Icons.Default.ContentPaste, onPaste)
        MenuRow("QR-код", Icons.Default.QrCodeScanner, onQr)
        MenuRow("Ручной ввод", Icons.Default.Add, onManual)
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = Accent); Spacer(Modifier.width(14.dp)); Text(label)
    }
}

@Composable
private fun ManualAddDialog(onAdd: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить подписку") }, text = { Column {
        OutlinedTextField(value, { value = it }, label = { Text("Ссылка или конфигурация") }, minLines = 3)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(title, { title = it }, label = { Text("Название (необязательно)") }, singleLine = true)
    } }, confirmButton = { Button(onClick = { onAdd(value, title.takeIf { it.isNotBlank() }) }, enabled = value.isNotBlank()) { Text("Добавить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun ServerDialog(servers: List<ServerProfile>, selected: ServerProfile?, onSelect: (ServerProfile) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Выберите сервер") }, text = { LazyColumn {
        items(servers, key = { it.id }) { server ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(server) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(server.countryFlag.ifBlank { "◇" }, fontSize = 22.sp); Spacer(Modifier.width(10.dp)); Column {
                    Text(server.name, fontWeight = if (server.id == selected?.id) FontWeight.Bold else FontWeight.Normal)
                    Text(server.scheme.uppercase(), color = Muted, fontSize = 11.sp)
                }
            }
        }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Настройки") }, text = { Column {
        Text("Интерфейс", color = Accent, fontWeight = FontWeight.SemiBold)
        Text("Тёмная тема включена", modifier = Modifier.padding(vertical = 12.dp))
        Divider()
        Text("Туннель", color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text("Маршрутизация и приложения будут доступны после подключения VPN-ядра.", color = Muted, modifier = Modifier.padding(top = 8.dp))
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } })
}
