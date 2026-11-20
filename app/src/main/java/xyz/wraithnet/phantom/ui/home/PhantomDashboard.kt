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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** Correct Russian declension also for lone keys and double-digit counts. */
internal fun serverCountLabel(count: Int): String {
    val suffix = if (count % 100 in 11..14) "серверов" else when (count % 10) {
        1 -> "сервер"
        2, 3, 4 -> "сервера"
        else -> "серверов"
    }
    return "$count $suffix"
}

@Composable
private fun SubscriptionHeading(
    sub: Subscription,
    displayTitle: String,
    refreshing: Boolean,
    probing: Boolean,
    failed: Boolean,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onRefresh: (Subscription) -> Unit,
    onProbe: (Subscription) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val remote = sub.source.startsWith("https://", true) || sub.source.startsWith("http://", true)
    val canProbe = remember(sub.servers) { sub.servers.any { ProtocolSupport.isSupported(it.scheme) } }
    Panel(selected = selected) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 58.dp).tvFocusRing()
                .clickable(onClick = onToggle)
                .semantics { contentDescription = (if (expanded) "Свернуть" else "Раскрыть") +
                    " подписку " + displayTitle }
                .padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    null, modifier = Modifier.size(22.dp), tint = c.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(if (remote) "${serverCountLabel(sub.servers.size)} · ${timeLabel(sub.updatedAt)}"
                        else "Импортированная конфигурация · ${serverCountLabel(sub.servers.size)}",
                        color = c.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                    if (failed) Text("Ошибка обновления · серверы сохранены",
                        color = c.error, fontSize = 11.sp, maxLines = 2)
                }
            }
            if (remote) {
                IconButton(
                    onClick = { onRefresh(sub) },
                    enabled = !refreshing,
                    modifier = Modifier.size(48.dp).tvFocusRing()
                        .semantics { contentDescription = "Обновить подписку ${displayTitle}" },
                ) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null, Modifier.size(22.dp), tint = c.primary)
                }
            }
            IconButton(
                onClick = { onProbe(sub) }, enabled = canProbe && !probing,
                modifier = Modifier.size(48.dp).tvFocusRing()
                    .semantics { contentDescription = "Проверить задержку подписки $displayTitle" },
                colors = IconButtonDefaults.iconButtonColors(contentColor = c.primary),
            ) {
                if (probing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Speed, null, Modifier.size(22.dp))
            }
        }
    }
}

@Composable
internal fun ServersPage(
    subscriptions: List<Subscription>, selected: Pair<Subscription, ServerProfile>?,
    pinnedId: String?, favorites: Set<String>, latency: Map<String, Long>,
    probing: Set<String>, measuring: Map<String, String>, refreshing: Set<String>, failed: Set<String>,
    onRefresh: (Subscription) -> Unit, onProbe: (Subscription) -> Unit,
    onAutoProbe: (Subscription) -> Unit, favorite: (String) -> Unit,
    choose: (Subscription, ServerProfile) -> Unit, add: () -> Unit,
) {
    // Keep one group open to avoid an overwhelming combined server list.
    // The selected server's owner is expanded by default, including on TV.
    var expandedId by rememberSaveable {
        mutableStateOf(selected?.first?.id ?: pinnedId ?: subscriptions.firstOrNull()?.id)
    }
    LaunchedEffect(subscriptions.map { it.id }) {
        if (expandedId != null && subscriptions.none { it.id == expandedId }) {
            expandedId = selected?.first?.id ?: subscriptions.firstOrNull()?.id
        }
    }
    val expandedOwner = subscriptions.firstOrNull { it.id == expandedId }
    val expandedProbing = expandedId in probing
    LaunchedEffect(expandedOwner?.id, expandedOwner?.servers, expandedProbing) {
        if (!expandedProbing) expandedOwner?.let(onAutoProbe)
    }
    // Sort large groups only when subscriptions/favorites change, not on every
    // recomposition triggered by a ping or another subscription's progress.
    val groups = remember(subscriptions, pinnedId, favorites) {
        subscriptions.sortedByDescending { it.id == pinnedId }.map { owner ->
            owner to owner.servers.sortedWith(
                compareByDescending<ServerProfile>(ProfileSelection::isProviderAutomatic)
                    .thenByDescending { "${owner.id}:${it.id}" in favorites }
            )
        }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (groups.isEmpty()) item(key = "empty") {
            Text("Добавьте подписку или ключ, чтобы увидеть серверы",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        groups.forEach { (owner, orderedServers) ->
            item(key = "subscription:${owner.id}") {
                SubscriptionHeading(
                    sub = owner, displayTitle = subscriptionDisplayName(owner, subscriptions),
                    refreshing = owner.id in refreshing, probing = owner.id in probing, failed = owner.id in failed,
                    expanded = expandedId == owner.id, selected = selected?.first?.id == owner.id,
                    onToggle = {
                        expandedId = if (expandedId == owner.id) null else owner.id
                    },
                    onRefresh = onRefresh, onProbe = onProbe,
                )
            }
            if (expandedId != owner.id) return@forEach
            items(orderedServers, key = { "server:${owner.id}:${it.id}" }) { server ->
                val id = "${owner.id}:${server.id}"
                val chosen = owner.id == selected?.first?.id && server.id == selected?.second?.id
                val remembered = owner.id != selected?.first?.id && server.id == owner.selectedServerId
                Panel(chosen || remembered) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).tvFocusRing().clickable { choose(owner, server) }
                            .semantics { contentDescription = "Выбрать сервер ${displayServerName(server)}" }
                            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ProfileSelection.isProviderAutomatic(server))
                                Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                            else Text(CountryFlags.flag(server), fontSize = 23.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(displayServerName(server), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Caption(protocolLabel(server))
                            }
                            Caption(SubscriptionPingText.display(latency[id], measuring[id] == server.raw))
                            if (chosen || remembered) Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(modifier = Modifier.tvFocusRing(), onClick = { favorite(id) }) {
                            Icon(if (id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                if (id in favorites) "Убрать из избранного" else "В избранное",
                                tint = if (id in favorites) Color(0xFFCEB36D)
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item(key = "manual-add") {
            OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().tvFocusRing()
                .heightIn(min = 48.dp)) {
                Icon(Icons.Default.Edit, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Добавить ключ вручную")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppUpdateSheet(
    release: PhantomRelease,
    downloading: Boolean,
    progress: Int,
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
                    Text("Обновление приложения", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
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
                Caption("Загрузка $progress%")
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
private fun DetailedSubscription(sub: Subscription, remote: Boolean, openUrl: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val now = System.currentTimeMillis() / 1000L
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (!remote) {
            Caption("Отдельный ключ не передаёт срок и лимит трафика")
            return@Column
        }
        val downloaded = sub.downloadBytes
        val uploaded = sub.uploadBytes
        val used = if (downloaded != null && uploaded != null)
            runCatching { Math.addExact(downloaded, uploaded) }.getOrDefault(Long.MAX_VALUE).coerceAtLeast(0L)
        else null
        val limit = sub.totalBytes
        if (used != null || limit != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Трафик", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text((used?.let(::sizeLabel) ?: "—") + " / " + when(limit) {
                    null -> "—"
                    0L -> "∞"
                    else -> sizeLabel(limit.coerceAtLeast(0L))
                }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            if (used != null && limit != null && limit > 0)
                LinearProgressIndicator(progress = {
                    (used.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                    color = c.primary, trackColor = c.surfaceVariant)
        } else Caption("Провайдер не передал данные о трафике")
        val expiry = sub.expiresAt
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                expiry == null -> "Срок действия не предоставлен"
                expiry == 0L -> "Без ограничения срока"
                expiry <= now -> "Срок истёк " + dateLabel(expiry)
                else -> "Действует до " + dateLabel(expiry)
            }, modifier = Modifier.weight(1f), fontSize = 14.sp,
                color = if (expiry != null && expiry > 0 && expiry <= now) c.error else c.onSurface)
            if (expiry != null && expiry > now)
                Text(daysLeft(expiry, now).toString() + " дн.", fontSize = 12.sp, color = c.onSurfaceVariant)
        }
        Caption("Последнее обновление · " + timeLabel(sub.updatedAt))
        Caption("Автообновление · " + if (sub.updateIntervalSeconds < 3600L)
            (sub.updateIntervalSeconds / 60L).toString() + " мин."
        else (sub.updateIntervalSeconds / 3600L).toString() + " ч.")
        sub.announcement?.takeIf { it.isNotBlank() }?.let { announcement ->
            Surface(shape = RoundedCornerShape(12.dp),
                color = c.surfaceVariant.copy(alpha = .6f)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = c.primary)
                    Text(announcement, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                }
            }
        }
        if (sub.supportUrl != null || sub.webPageUrl != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sub.webPageUrl?.let { url ->
                    TextButton(onClick = { openUrl(url) }, modifier = Modifier.tvFocusRing()) {
                        Icon(Icons.Default.OpenInBrowser, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Кабинет")
                    }
                }
                sub.supportUrl?.let { url ->
                    TextButton(onClick = { openUrl(url) }, modifier = Modifier.tvFocusRing()) {
                        Icon(Icons.Default.SupportAgent, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Поддержка")
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionPage(subscriptions: List<Subscription>, currentId: String?,
    pinnedId: String?, refreshing: Set<String>, failed: Set<String>,
    onRefresh: (Subscription) -> Unit, manage: (Subscription) -> Unit,
    add: () -> Unit, shop: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(pinnedId ?: currentId ?: subscriptions.firstOrNull()?.id) }
    LaunchedEffect(pinnedId, subscriptions.map { it.id }) {
        if (pinnedId != null && subscriptions.any { it.id == pinnedId }) expanded = pinnedId
        else if (expanded != null && subscriptions.none { it.id == expanded })
            expanded = currentId ?: subscriptions.firstOrNull()?.id
    }
    fun openUrl(url: String) {
        if (SubscriptionHeaders.safeHttpUrl(url) != url) return
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)).addCategory(android.content.Intent.CATEGORY_BROWSABLE))
        }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (subscriptions.isEmpty()) item {
            Panel {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(crown, null, Modifier.size(34.dp), tint = c.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Подписок пока нет", fontWeight = FontWeight.Medium)
                    Caption("Добавьте подписку или отдельный ключ")
                }
            }
        }
        items(subscriptions, key = { it.id }) { sub ->
            val name = subscriptionDisplayName(sub, subscriptions)
            val remote = sub.source.startsWith("https://", true) || sub.source.startsWith("http://", true)
            val isExpanded = expanded == sub.id
            Panel(selected = sub.id == currentId) {
                Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 55.dp).tvFocusRing()
                        .clickable { expanded = if (isExpanded) null else sub.id }
                        .semantics { contentDescription = (if (isExpanded) "Свернуть" else "Раскрыть") +
                            " подписку $name" }.padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            null, Modifier.size(22.dp), tint = c.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, modifier = Modifier.weight(1f, fill = false),
                                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (pinnedId == sub.id) Icon(Icons.Default.PushPin, "Закреплена",
                                    Modifier.padding(start = 6.dp).size(15.dp), tint = c.primary)
                            }
                            Caption(if (remote) serverCountLabel(sub.servers.size) + " · " + timeLabel(sub.updatedAt)
                                else "Ключ или конфигурация · " + serverCountLabel(sub.servers.size))
                            if (sub.id in failed)
                                Text("Ошибка обновления · серверы сохранены", fontSize = 11.sp, color = c.error)
                        }
                    }
                    if (remote) IconButton(onClick = { onRefresh(sub) }, enabled = sub.id !in refreshing,
                        modifier = Modifier.size(48.dp).tvFocusRing().semantics {
                            contentDescription = "Обновить подписку $name"
                        }) {
                        if (sub.id in refreshing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null, Modifier.size(21.dp), tint = c.primary)
                    }
                    IconButton(onClick = { manage(sub) }, modifier = Modifier.size(48.dp).tvFocusRing()) {
                        Icon(Icons.Default.MoreVert, "Управление подпиской $name")
                    }
                }
                if (isExpanded) {
                    HorizontalDivider(color = c.outline.copy(alpha = .2f),
                        modifier = Modifier.padding(bottom = 12.dp))
                    DetailedSubscription(sub, remote, ::openUrl)
                }
            }
        }
        item {
            OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().tvFocusRing()
                .heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Добавить подписку или ключ")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { shop(true) }, modifier = Modifier.tvFocusRing()) {
                    Text("Продлить Phantom")
                }
                Text("·", color = c.onSurfaceVariant)
                TextButton(onClick = { shop(false) }, modifier = Modifier.tvFocusRing()) {
                    Text("Купить Phantom")
                }
            }
        }
        item {
            Text("В магазине выберите „Моя подписка“ → „Продлить“",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .semantics { contentDescription = "В магазине выберите „Моя подписка“ → „Продлить“" },
                textAlign = TextAlign.Center, softWrap = true,
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant)
        }
    }
}
