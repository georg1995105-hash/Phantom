package xyz.wraithnet.phantom.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.drawscope.Stroke
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
            NavigationBarItem(modifier = Modifier.tvFocusRing().semantics { contentDescription = "Вкладка ${page.title}" }, selected = current == page, onClick = { select(page) },
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
    subscriptionAvailable: Boolean, hasSubscriptions: Boolean) {
    val c = MaterialTheme.colorScheme
    val tv = isTelevision()
    val initialFocus = remember { FocusRequester() }
    val connected = status == "connected"
    val starting = status == "starting"
    val ring by animateColorAsState(if (connected || starting) c.primary else c.outline, label = "connection")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = if (!hasSubscriptions) 12.dp else (maxHeight * .10f).coerceIn(12.dp, 72.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(gap))
            val rim = Brush.sweepGradient(listOf(ring.copy(alpha = .35f), c.outline.copy(alpha = .18f),
                c.outline.copy(alpha = .2f), if (connected) c.primary else ring.copy(alpha = .42f),
                ring.copy(alpha = .35f)))
            Box(Modifier.size(if (tv) 104.dp else if (!hasSubscriptions) 112.dp else 150.dp).border(if (connected) 2.dp else 1.dp, rim, CircleShape)
                .padding(6.dp).clip(CircleShape).background(Brush.linearGradient(listOf(
                    if (connected) c.primaryContainer else c.surfaceVariant, c.background, c.surface)))
                .border(1.dp, ring.copy(alpha = .7f), CircleShape).focusRequester(initialFocus).tvFocusRing(CircleShape)
                // Disabling the focused TV control during the handshake moves D-pad focus elsewhere.
                .clickable(enabled = tv || !starting) { if (!starting) toggle() },
                contentAlignment = Alignment.Center) {
                if (starting) CircularProgressIndicator(Modifier.fillMaxSize().padding(3.dp), strokeWidth = 2.dp)
                Icon(Icons.Default.PowerSettingsNew, when {
                    connected -> "Отключить VPN"
                    starting -> "Подключение"
                    else -> "Подключить VPN"
                },
                    Modifier.size(62.dp), tint = c.onSurface)
            }
            LaunchedEffect(tv) { if (tv && !starting) initialFocus.requestFocus() }
            Spacer(Modifier.height(16.dp))
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
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onRefreshSubscription,
                enabled = subscriptionAvailable && !subscriptionRefreshing,
                modifier = Modifier.tvFocusRing().heightIn(min = 48.dp).semantics {
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
            if (error != null) TextButton(onClick = error) { Text("Причина ошибки") }
            Spacer(Modifier.height(12.dp))
            Panel {
                Row(Modifier.fillMaxWidth().tvFocusRing().clickable(onClick = choose).padding(16.dp),
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
internal fun ConnectionImportActions(
    onQuickImport: () -> Unit,
    onScanQr: (() -> Unit)?,
    hasSubscriptions: Boolean,
    importing: Boolean,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (!hasSubscriptions) {
            Text("Скопируйте ссылку на подписку и нажмите «Добавить подписку»",
                modifier = Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, color = c.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onQuickImport, enabled = !importing,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing()
                    .semantics { contentDescription = "Добавить подписку" },
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer),
            ) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Добавить подписку", fontSize = 13.sp)
            }
            if (onScanQr != null) {
                OutlinedIconButton(
                    onClick = onScanQr,
                    enabled = !importing,
                    modifier = Modifier.size(48.dp).tvFocusRing()
                        .semantics { contentDescription = "Сканировать QR-код подписки" },
                    colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = c.primary),
                ) {
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

/** Identical subscription titles are distinguished without exposing private URLs. */
internal fun subscriptionDisplayName(owner: Subscription, all: List<Subscription>): String {
    val duplicates = all.filter { it.title.trim().equals(owner.title.trim(), ignoreCase = true) }
    return if (duplicates.size <= 1) owner.title
        else "${owner.title} · ${duplicates.indexOfFirst { it.id == owner.id } + 1}"
}

@Composable
internal fun ServersPage(
    subscriptions: List<Subscription>, selected: Pair<Subscription, ServerProfile>?,
    pinnedId: String?, favorites: Set<String>, latency: Map<String, Long>,
    probing: Set<String>, measuring: Map<String, String>, refreshing: Set<String>, failed: Set<String>,
    onRefresh: (Subscription) -> Unit, manage: (Subscription) -> Unit,
    onAutoProbe: (Subscription) -> Unit, favorite: (String) -> Unit,
    choose: (Subscription, ServerProfile) -> Unit, add: () -> Unit, openUrl: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(arrayListOf<String>().apply {
        (selected?.first?.id ?: pinnedId ?: subscriptions.firstOrNull()?.id)?.let { this.add(it) }
    }) }
    var details by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val groups = remember(subscriptions, pinnedId, favorites) {
        subscriptions.sortedByDescending { it.id == pinnedId }.map { owner ->
            owner to owner.servers.sortedWith(compareByDescending<ServerProfile>(ProfileSelection::isProviderAutomatic)
                .thenByDescending { "${owner.id}:${it.id}" in favorites })
        }
    }
    val c = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        if (groups.isEmpty()) item {
            Text("Добавьте подписку или ключ, чтобы увидеть серверы", Modifier.padding(12.dp))
        }
        groups.forEach { (owner, servers) ->
            val remote = owner.source.startsWith("https://", true) || owner.source.startsWith("http://", true)
            val single = !remote && servers.size == 1
            val show = owner.id in expanded
            val name = subscriptionDisplayName(owner, subscriptions)
            if (!single) item(key = "header:${owner.id}") {
                Column(Modifier.groupSurface(first = true, last = !show || servers.isEmpty())) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 58.dp).tvFocusRing().clickable {
                            expanded = ArrayList(if (show) expanded - owner.id else expanded + owner.id)
                        }.semantics { contentDescription = (if (show) "Свернуть" else "Раскрыть") + " подписку " + name },
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (show) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null,
                                Modifier.padding(horizontal = 6.dp).size(18.dp), tint = c.primary)
                            Column {
                                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (remote) {
                                    val time = android.text.format.DateFormat.getTimeFormat(
                                        androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(owner.updatedAt))
                                    val interval = intervalLabel(owner.customIntervalSeconds ?: owner.updateIntervalSeconds)
                                    Caption(if (owner.updatedAt > 0) "Обновлено $time · $interval" else interval)
                                }
                                if (owner.id in failed) Text("Ошибка обновления · серверы сохранены", color = c.error, fontSize = 11.sp)
                            }
                        }
                        if (remote) IconButton(onClick = { onRefresh(owner) }, enabled = owner.id !in refreshing,
                            modifier = Modifier.tvFocusRing().semantics { contentDescription = "Обновить подписку $name" }) {
                            if (owner.id in refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, null, tint = c.primary)
                        }
                        OwnerMenu(name) { manage(owner) }
                    }
                    if (remote) ProviderInfo(owner, owner.id in details, {
                        details = ArrayList(if (owner.id in details) details - owner.id else details + owner.id)
                    }, openUrl)
                    if (show && owner.id !in probing) LaunchedEffect(owner.id, owner.servers) { onAutoProbe(owner) }
                }
            }
            if (single || show) items(servers.size, key = { "server:${owner.id}:${servers[it].id}" }) { index ->
                val server = servers[index]
                val id = "${owner.id}:${server.id}"
                val chosen = owner.id == selected?.first?.id && server.id == selected?.second?.id
                val remembered = owner.id != selected?.first?.id && owner.selectedServerId == server.id
                Column(Modifier.groupSurface(first = single, last = index == servers.lastIndex)) {
                    if (!single) HorizontalDivider(color = c.outline.copy(alpha = .22f))
                    Row(Modifier.fillMaxWidth().background(when {
                        chosen -> c.primary.copy(alpha = .12f)
                        remembered -> c.primary.copy(alpha = .06f)
                        else -> Color.Transparent
                    })
                        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (chosen) Spacer(Modifier.width(3.dp).height(24.dp)
                            .background(c.primary, RoundedCornerShape(2.dp)))
                        Row(Modifier.weight(1f).heightIn(min = 54.dp).tvFocusRing().clickable { choose(owner, server) }
                            .semantics { contentDescription = "Выбрать сервер ${displayServerName(server)}" }
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(CountryFlags.flag(server), fontSize = 19.sp)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(if (single) owner.customTitle ?: displayServerName(server) else displayServerName(server),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Caption(protocolLabel(server))
                            }
                            Caption(SubscriptionPingText.display(latency[id], measuring[id] == server.raw))
                        }
                        IconButton(onClick = { favorite(id) }, modifier = Modifier.tvFocusRing()) {
                            Icon(if (id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                if (id in favorites) "Убрать из избранного" else "В избранное", Modifier.size(19.dp),
                                tint = if (id in favorites) Color(0xFFCEB36D) else c.onSurfaceVariant)
                        }
                        if (single) OwnerMenu(name) { manage(owner) }
                    }
                    if (single && owner.id !in probing) LaunchedEffect(owner.id, server.raw) { onAutoProbe(owner) }
                }
            }
            item(key = "gap:${owner.id}") { Spacer(Modifier.height(10.dp)) }
        }
        item {
            OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().tvFocusRing().heightIn(min = 48.dp)) {
                Text("Добавить подписку или ключ")
            }
        }
    }
}

// Draw one continuous outline while keeping individual server rows lazy.
@Composable
private fun Modifier.groupSurface(first: Boolean, last: Boolean): Modifier {
    val c = MaterialTheme.colorScheme
    val radius = 18.dp
    val shape = RoundedCornerShape(if (first) radius else 0.dp, if (first) radius else 0.dp,
        if (last) radius else 0.dp, if (last) radius else 0.dp)
    return clip(shape).background(c.surface).drawWithContent {
        drawContent()
        val stroke = 1.dp.toPx()
        val r = radius.toPx()
        val top = if (first) stroke / 2 else -r
        val bottom = if (last) size.height - stroke / 2 else size.height + r
        drawRoundRect(c.primary.copy(alpha = .34f), Offset(stroke / 2, top),
            Size(size.width - stroke, bottom - top), CornerRadius(r), style = Stroke(stroke))
    }
}

@Composable
private fun OwnerMenu(name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.tvFocusRing()) {
        Icon(Icons.Default.MoreVert, "Управление подпиской $name")
    }
}

internal fun intervalLabel(seconds: Long): String {
    return when {
        seconds == 3600L -> "Каждый час"
        seconds % 3600L == 0L -> "Каждые ${seconds / 3600} ч."
        seconds % 60L == 0L -> "Каждые ${seconds / 60} мин."
        else -> "Интервал $seconds с"
    }
}

@Composable
private fun ProviderInfo(sub: Subscription, details: Boolean, toggle: () -> Unit, openUrl: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val upload = sub.uploadBytes
    val download = sub.downloadBytes
    val traffic = when {
        upload != null && download != null -> sizeLabel(runCatching { Math.addExact(upload, download) }.getOrDefault(Long.MAX_VALUE))
        upload != null -> "↑ " + sizeLabel(upload)
        download != null -> "↓ " + sizeLabel(download)
        else -> null
    }
    val limit = sub.totalBytes?.let { if (it == 0L) "∞" else sizeLabel(it) }
    val expiry = sub.expiresAt
    val account = SubscriptionHeaders.safeHttpUrl(sub.webPageUrl)
    val support = SubscriptionHeaders.safeHttpUrl(sub.supportUrl)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        if (listOf(traffic, limit, expiry, account, support).any { it != null }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (account != null) IconButton(onClick = { openUrl(account) }, modifier = Modifier.tvFocusRing()) {
                    Icon(Icons.Default.Info, "Кабинет ${sub.title}", Modifier.size(20.dp), tint = c.primary)
                }
                if (traffic != null || limit != null) Column(Modifier.weight(1f)) {
                    Caption(if (traffic == null) "Лимит" else "Трафик")
                    Text(listOfNotNull(traffic, limit).joinToString(" / "), fontSize = 14.sp)
                } else Spacer(Modifier.weight(1f))
                if (expiry != null) Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Caption("Действует до")
                    Text(if (expiry == 0L) "Бессрочно" else dateLabel(expiry), fontSize = 14.sp)
                }
                if (support != null) IconButton(onClick = { openUrl(support) }, modifier = Modifier.tvFocusRing()) {
                    val host = android.net.Uri.parse(support).host?.lowercase()
                    Icon(if (host in setOf("t.me", "telegram.me")) Icons.Default.Send else Icons.Default.SupportAgent,
                        "Поддержка ${sub.title}", Modifier.size(20.dp), tint = c.primary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (expiry != null && expiry > 0) Caption("${daysLeft(expiry, System.currentTimeMillis() / 1000)} дн.")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = toggle, modifier = Modifier.tvFocusRing().semantics { contentDescription = "Подробнее о ${sub.title}" }) {
                Text("Подробнее")
                Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp))
            }
        }
        if (details) {
            sub.announcement?.takeIf(String::isNotBlank)?.let { Text(it) }
            if (sub.updatedAt > 0) Caption("Обновлено " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(sub.updatedAt)))
            Caption("Автообновление · " + intervalLabel(sub.customIntervalSeconds ?: sub.updateIntervalSeconds))
            upload?.let { Caption("Отправлено · " + sizeLabel(it)) }
            download?.let { Caption("Получено · " + sizeLabel(it)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppUpdateSheet(
    release: PhantomRelease,
    downloading: Boolean,
    progress: Int,
    speedBytesPerSecond: Long,
    readyToInstall: Boolean,
    error: String?,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val brief = remember(release.notes) { ReleaseNotes.summary(release.notes, 3) }
    val details = remember(release.notes) { ReleaseNotes.summary(release.notes, 15) }
    var expanded by rememberSaveable(release.version) { mutableStateOf(false) }
    val downloadInProgress by rememberUpdatedState(downloading)
    ModalBottomSheet(
        onDismissRequest = { if (!downloading) onLater() },
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !downloadInProgress }),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdateAlt, null, tint = c.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (xyz.wraithnet.phantom.BuildConfig.UPDATE_CHANNEL == "beta")
                            "Обновление Phantom Beta" else "Обновление Phantom",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Caption("Версия ${release.version}")
                }
            }
            if (brief.isNotBlank()) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    Text(if (expanded) details else brief,
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (brief != details) {
                        TextButton(onClick = { expanded = !expanded },
                            modifier = Modifier.align(Alignment.End).tvFocusRing()) {
                            Text(if (expanded) "Меньше" else "Ещё")
                        }
                    }
                }
            }
            if (downloading) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Caption("Загрузка $progress%" + if (speedBytesPerSecond > 0L)
                    " · ${speedBytesPerSecond / 1024L} КБ/с" else "")
            }
            error?.let { Text(it, color = c.error, style = MaterialTheme.typography.bodySmall) }
            val shape = RoundedCornerShape(14.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onLater, enabled = !downloading, shape = shape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = c.surfaceVariant, contentColor = c.onSurface),
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp).tvFocusRing(shape)) {
                    Text("Позже")
                }
                Button(onClick = onUpdate, enabled = !downloading, shape = shape,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp).tvFocusRing(shape)) {
                    Text(when {
                        downloading -> "Скачивание…"
                        readyToInstall -> "Установить"
                        else -> "Обновить"
                    })
                }
            }
        }
    }
}

internal fun daysLeft(expiry: Long, now: Long): Long =
    if (expiry <= now) 0L else (expiry - now - 1L) / 86400L + 1L

@Composable
internal fun SubscriptionPage(openShop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 6.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.primary.copy(alpha = .38f))) {
            Column {
                Box(Modifier.fillMaxWidth().background(Brush.linearGradient(
                    listOf(Color(0xFFB5F5D0), Color(0xFFE6F8EA))))) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 54.dp, y = (-56).dp)
                        .size(192.dp).border(1.dp, Color(0x5523754C), CircleShape))
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 35.dp, y = (-38).dp)
                        .size(152.dp).border(1.dp, Color(0x3323754C), CircleShape))
                    Column(Modifier.padding(horizontal = 23.dp, vertical = 24.dp)) {
                        Text("PHANTOM VPN", color = Color(0xFF133A27),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(26.dp))
                        Text("ВАШ\nДОСТУП.", color = Color(0xFF0C2D1D),
                            fontSize = 43.sp, lineHeight = 44.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
                        Spacer(Modifier.height(12.dp))
                        Text("ОФИЦИАЛЬНЫЙ МАГАЗИН", color = Color(0xFF24533B),
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    }
                }
                Column(Modifier.padding(20.dp)) {
                    Text("Без лишних действий",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Покупка и продление в официальном магазине.",
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = openShop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvFocusRing(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFA0EABD), contentColor = Color(0xFF0D271B))) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("В магазин", fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.NorthEast, null, Modifier.size(19.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = colors.outline.copy(alpha = .25f))
                    Spacer(Modifier.height(13.dp))
                    Caption("Продлить: в магазине «Моя подписка» → «Продлить».")
                }
            }
        }
    }
}
