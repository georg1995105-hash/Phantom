package xyz.wraithnet.phantom.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.wraithnet.phantom.R
import xyz.wraithnet.phantom.data.*
import java.util.Locale

private val crown = ImageVector.Builder("Корона", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
        strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
        moveTo(2.5f, 7f)
        lineTo(8f, 11f)
        lineTo(12f, 4f)
        lineTo(16f, 11f)
        lineTo(21.5f, 7f)
        lineTo(19f, 17f)
        lineTo(5f, 17f)
        close()
        moveTo(5.5f, 20f)
        lineTo(18.5f, 20f)
    }
}.build()

internal enum class Destination(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Default.Home),
    SERVERS("Серверы", Icons.Default.Storage),
    SUBSCRIPTIONS("Подписка", crown),
    SETTINGS("Настройки", Icons.Default.Settings)
}

@Composable
internal fun TabBar(current: Destination, select: (Destination) -> Unit) {
    val c = MaterialTheme.colorScheme
    NavigationBar(modifier = Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        containerColor = c.surface, tonalElevation = 0.dp) {
        Destination.entries.forEach { page ->
            NavigationBarItem(selected = current == page, onClick = { select(page) },
                icon = { Icon(page.icon, null) }, label = { Text(page.title, fontSize = 11.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = c.primary,
                    selectedTextColor = c.primary, indicatorColor = c.primaryContainer,
                    unselectedIconColor = c.onSurfaceVariant, unselectedTextColor = c.onSurfaceVariant))
        }
    }
}

@Composable
internal fun Panel(selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp,
        color = c.surface, border = BorderStroke(1.dp, if (selected) c.primary else c.outline.copy(alpha = .35f))) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
            c.surfaceVariant.copy(alpha = .48f), c.surface))), content = content)
    }
}

@Composable
internal fun PageTitle(page: Destination) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (page == Destination.HOME) {
            Icon(painterResource(R.drawable.ic_stat_phantom), null, Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (page == Destination.HOME) "Phantom" else page.title,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ConnectionPage(status: String, server: ServerProfile?, download: Long, upload: Long,
    seconds: Long, toggle: () -> Unit, choose: () -> Unit, error: (() -> Unit)?,
    onRefreshSubscription: () -> Unit, subscriptionRefreshing: Boolean,
    subscriptionAvailable: Boolean) {
    val c = MaterialTheme.colorScheme
    val connected = status == "connected"
    val starting = status == "starting"
    val ring by animateColorAsState(if (connected || starting) c.primary else c.outline, label = "connection")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = (maxHeight * .10f).coerceIn(12.dp, 72.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(gap))
            val rim = Brush.sweepGradient(listOf(ring.copy(alpha = .35f), c.outline.copy(alpha = .18f),
                c.outline.copy(alpha = .2f), if (connected) c.primary else ring.copy(alpha = .42f),
                ring.copy(alpha = .35f)))
            Box(Modifier.size(150.dp).border(if (connected) 2.dp else 1.dp, rim, CircleShape)
                .padding(6.dp).clip(CircleShape).background(Brush.linearGradient(listOf(
                    if (connected) c.primaryContainer else c.surfaceVariant, c.background, c.surface)))
                .border(1.dp, ring.copy(alpha = .7f), CircleShape).clickable(enabled = !starting, onClick = toggle),
                contentAlignment = Alignment.Center) {
                if (starting) CircularProgressIndicator(Modifier.fillMaxSize().padding(3.dp), strokeWidth = 2.dp)
                Icon(Icons.Default.PowerSettingsNew, if (connected) "Отключить VPN" else "Подключить VPN",
                    Modifier.size(62.dp), tint = c.onSurface)
            }
            Spacer(Modifier.height(16.dp))
            // Refresh the selected VPN subscription next to the connection state.
            // Weighted status text wraps cleanly on narrower phones.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                if (connected) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(17.dp), tint = c.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(when (status) {
                    "connected" -> "Подключено"
                    "starting" -> "Подключение…"
                    "error" -> "Соединение не установлено"
                    else -> "Отключено"
                }, color = if (connected) c.primary else c.onBackground,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRefreshSubscription,
                    enabled = subscriptionAvailable && !subscriptionRefreshing,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Обновить выбранную подписку"
                    }) {
                    if (subscriptionRefreshing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Обновить", fontSize = 13.sp)
                }
            }
            if (error != null) TextButton(onClick = error) { Text("Причина ошибки") }
            Spacer(Modifier.height(12.dp))
            Panel {
                Row(Modifier.fillMaxWidth().clickable(onClick = choose).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (server != null) Text(CountryFlags.flag(server), fontSize = 28.sp)
                    else Icon(Icons.Default.Storage, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server?.let(::displayServerName) ?: "Выбрать сервер", fontWeight = FontWeight.SemiBold)
                        server?.let { Caption(protocolLabel(it)) }
                    }
                    Icon(Icons.Default.ChevronRight, "К серверам")
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp)) {
                    val time = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                    listOf((if (download < 0) "—" else sizeLabel(download)) to "Входящий", (if (upload < 0) "—" else sizeLabel(upload)) to "Исходящий",
                        time to "Время").forEach { (value, label) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Caption(label)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ServersPage(subscriptions: List<Subscription>, selected: Pair<Subscription, ServerProfile>?,
    pinnedId: String?, favorites: Set<String>, latency: Map<String, LatencyProbe.PingResult>,
    probing: Set<String>, checkPing: () -> Unit, favorite: (String) -> Unit,
    choose: (Subscription, ServerProfile) -> Unit, add: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(subscriptions, favorites) {
        subscriptions.flatMap { owner -> owner.servers.map { owner to it } }
            .sortedWith(compareByDescending<Pair<Subscription, ServerProfile>> { it.second.name.contains("Автовыбор", true) }
                .thenByDescending { (owner, server) -> "${owner.id}:${server.id}" in favorites })
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = checkPing, enabled = rows.isNotEmpty() && probing.isEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (probing.isNotEmpty()) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Speed, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (probing.isNotEmpty()) "Проверка пинга…" else "Проверить пинг")
            }
        }
        if (rows.none { it.second.name.contains("Автовыбор", true) }) {
            ProfileSelection.resolve(subscriptions, null, null, pinnedId)?.let { (owner, server) ->
                item {
                    Panel {
                        Row(Modifier.fillMaxWidth().clickable { choose(owner, server) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Автовыбор", fontWeight = FontWeight.SemiBold)
                                Caption("По умолчанию · " + displayServerName(server))
                            }
                        }
                    }
                }
            }
        }
        if (rows.size > 5) item {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Найти сервер") })
        }
        val filtered = rows.filter { (owner, server) -> rows.size <= 5 || server.name.contains(query, true) || owner.title.contains(query, true) }
        if (rows.isEmpty()) item { OutlinedButton(onClick = add) { Text("Добавить подписку или ключ") } }
        else if (filtered.isEmpty()) item { Caption("Серверы не найдены") }
        items(filtered) { (owner, server) ->
            val id = "${owner.id}:${server.id}"
            val chosen = owner.id == selected?.first?.id && server.id == selected?.second?.id
            Panel(chosen) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { choose(owner, server) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (server.name.contains("Автовыбор", true)) Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                        else Text(CountryFlags.flag(server), fontSize = 23.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(displayServerName(server), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Caption(protocolLabel(server))
                        }
                        Caption(latency[id]?.label ?: "—")
                        if (chosen) Icon(Icons.Default.CheckCircle, "Выбран", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { favorite(id) }) {
                        Icon(if (id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                            if (id in favorites) "Убрать из избранного" else "В избранное")
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionPage(subscriptions: List<Subscription>, currentId: String?, refreshing: Set<String>,
    failed: Set<String>, refresh: (Subscription) -> Unit, manage: (Subscription) -> Unit,
    probe: (Subscription) -> Unit, probing: Set<String>, add: () -> Unit, shop: (Boolean) -> Unit) {
    val remote = subscriptions.filter { it.source.startsWith("https://", true) || it.source.startsWith("http://", true) }
    var inspected by rememberSaveable { mutableStateOf<String?>(null) }
    val item = remote.find { it.id == inspected } ?: remote.find { it.id == currentId } ?: remote.firstOrNull()
    var menu by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(crown, null, Modifier.size(40.dp), tint = c.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(item?.title ?: "Нет добавленных подписок", fontWeight = FontWeight.SemiBold)
                    if (remote.size > 1) Box {
                        TextButton(onClick = { menu = true }) { Text("Выбрать подписку") }
                        DropdownMenu(menu, { menu = false }) {
                            remote.forEach { sub -> DropdownMenuItem(text = { Text(sub.title) },
                                onClick = { inspected = sub.id; menu = false }) }
                        }
                    }
                }
            }
        }
        if (item != null) item {
            Panel {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val expiry = item.expiresAt?.takeIf { it > 0 }
                    Text(when {
                        item.expiresAt == 0L -> "Без ограничения срока"
                        expiry == null -> "Срок действия не указан"
                        expiry <= System.currentTimeMillis() / 1000 -> "Срок истёк " + dateLabel(expiry)
                        else -> "Действует до " + dateLabel(expiry)
                    })
                    val used = if (item.downloadBytes != null && item.uploadBytes != null)
                        sizeLabel(item.downloadBytes + item.uploadBytes) else "—"
                    val limit = item.totalBytes?.let { if (it == 0L) "∞" else sizeLabel(it) } ?: "—"
                    Text("Трафик   $used / $limit")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Последнее обновление " + timeLabel(item.updatedAt),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant)
                        IconButton(onClick = { refresh(item) }, enabled = item.id !in refreshing,
                            modifier = Modifier.size(48.dp).semantics {
                                contentDescription = "Обновить подписку"
                            }) {
                            if (item.id in refreshing)
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, contentDescription = null,
                                modifier = Modifier.size(24.dp), tint = c.primary)
                        }
                    }
                    if (item.id in failed || System.currentTimeMillis() - item.updatedAt >= item.updateIntervalSeconds * 1000)
                        Caption("Данные могут быть устаревшими")
                }
            }
        }
        item {
            Button(onClick = { shop(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Продлить подписку Phantom")
            }
            Text("В магазине выберите „Моя подписка“ → „Продлить“",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center, softWrap = true,
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant)
        }
        item { OutlinedButton(onClick = { shop(false) }, modifier = Modifier.fillMaxWidth()) { Text("Купить подписку Phantom") } }
        item { OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("Добавить подписку или ключ") } }
        if (subscriptions.isNotEmpty()) item { Text("Мои подписки и ключи", style = MaterialTheme.typography.titleMedium) }
        items(subscriptions, key = { it.id }) { sub ->
            Panel {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { if (sub in remote) inspected = sub.id else manage(sub) }) {
                        Text(sub.title)
                        Caption(if (sub in remote) "Подписка · серверов: ${sub.servers.size}" else "Импортированная конфигурация")
                    }
                    IconButton(onClick = { probe(sub) }, enabled = sub.id !in probing) { Icon(Icons.Default.Speed, "Измерить задержку") }
                    IconButton(onClick = { manage(sub) }) { Icon(Icons.Default.MoreVert, "Управление") }
                }
            }
        }
    }
}
