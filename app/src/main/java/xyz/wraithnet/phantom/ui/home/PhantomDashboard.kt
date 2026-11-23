package xyz.wraithnet.phantom.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        Destination.entries.filter { it != Destination.SERVERS }.forEach { page ->
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
internal fun PageTitle(
    page: Destination,
    exit: ExitNetworkLookup.Info? = null,
    checkingExit: Boolean = false,
    openExit: () -> Unit = {},
) {
    val tv = isTelevision()
    Row(Modifier.fillMaxWidth().padding(
        horizontal = if (tv) 10.dp else 18.dp,
        vertical = if (tv) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (page == Destination.HOME) {
            Icon(painterResource(R.drawable.ic_stat_phantom), null, Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (page == Destination.HOME) "Phantom" else page.title,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (page == Destination.HOME && (exit != null || checkingExit)) {
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier.widthIn(max = 166.dp).clickable(enabled = exit != null, onClick = openExit),
                horizontalAlignment = Alignment.End
            ) {
                if (exit != null) {
                    Text(exit.flag() + " " + exit.countryName(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Сеть · " + exit.network.split(" trading as ", ignoreCase = true).first(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Определение сети…", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun ConnectionPage(status: String,
    seconds: Long, toggle: () -> Unit, error: (() -> Unit)?,
    hasSubscriptions: Boolean,
    tvFocusEpoch: Int = 0, tvModalVisible: Boolean = false,
    embedded: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val tv = isTelevision()
    val initialFocus = remember { FocusRequester() }
    val ownerView = LocalView.current
    var powerFocused by remember { mutableStateOf(false) }
    val connected = status == "connected"
    val starting = status == "starting"
    val ring by animateColorAsState(if (connected || starting) c.primary else c.outline, label = "connection")
    BoxWithConstraints(if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
        val gap = if (embedded) 5.dp else if (!hasSubscriptions) 12.dp
            else (maxHeight * .10f).coerceIn(12.dp, 72.dp)
        Column((if (embedded) Modifier.fillMaxWidth()
                else Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(gap))
            val rim = Brush.sweepGradient(listOf(ring.copy(alpha = .35f), c.outline.copy(alpha = .18f),
                c.outline.copy(alpha = .2f), if (connected) c.primary else ring.copy(alpha = .42f),
                ring.copy(alpha = .35f)))
            Box(Modifier.size(if (tv) 104.dp else if (embedded) 122.dp else if (!hasSubscriptions) 112.dp else 150.dp).border(if (connected) 2.dp else 1.dp, rim, CircleShape)
                .padding(6.dp).clip(CircleShape).background(Brush.linearGradient(listOf(
                    if (connected) c.primaryContainer else c.surfaceVariant, c.background, c.surface)))
                .border(1.dp, ring.copy(alpha = .7f), CircleShape).focusRequester(initialFocus)
                .onFocusChanged { powerFocused = it.isFocused }.tvFocusRing(CircleShape)
                // Disabling the focused TV control during the handshake moves D-pad focus elsewhere.
                .clickable(enabled = tv || !starting) { if (!starting) toggle() },
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    if (starting) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PowerSettingsNew, when {
                            connected -> "Отключить VPN"
                            else -> "Подключить VPN"
                        }, Modifier.size(if (embedded) 44.dp else 52.dp),
                            tint = c.onSurface)
                    }
                    // Only the power control shows the connection timer.
                    if (connected) {
                        Spacer(Modifier.height(3.dp))
                        Text(String.format(Locale.ROOT, "%02d:%02d:%02d",
                            seconds / 3600, seconds / 60 % 60, seconds % 60),
                            fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            // QR pairing and subscription dialogs remove D-pad focus. Restore
            // the main power control after each modal closes and on Activity
            // resume, not only at the first composition of the TV screen.
            LaunchedEffect(tv, tvFocusEpoch, tvModalVisible, starting) {
                if (tv && !tvModalVisible && !starting) {
                    // The Activity window regains focus asynchronously after dismissing
                    // a Compose Dialog. Request only when the power target is attached.
                    repeat(12) {
                        withFrameNanos { }
                        if (ownerView.hasWindowFocus()) {
                            // Restoring the host View first also handles a Dialog
                            // closed through a remote Back key after the Activity
                            // briefly had no focusable Compose target.
                            ownerView.requestFocus()
                            initialFocus.requestFocus()
                            withFrameNanos { }
                            if (powerFocused) return@LaunchedEffect
                        }
                        kotlinx.coroutines.delay(90)
                    }
                }
            }
            Spacer(Modifier.height(if (embedded) 10.dp else 16.dp))
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
            if (error != null) TextButton(onClick = error) { Text("Причина ошибки") }
            // The current country is already displayed in PageTitle on phones.
            // Keep the subscription list immediately below the connected state.
            Spacer(Modifier.height(if (embedded) 2.dp else 8.dp))

        }
    }
}

@Composable
internal fun ConnectionImportActions(
    onOpenImport: () -> Unit,
    onScanQr: (() -> Unit)?,
    onTvPair: (() -> Unit)? = null,
    hasSubscriptions: Boolean,
    importing: Boolean,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (!hasSubscriptions) {
            Text(if (onTvPair != null)
                "Отсканируйте QR-код телевизора в Phantom на телефоне или введите ссылку."
                else "Добавьте подписку или ключ через меню.",
                modifier = Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, color = c.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onOpenImport, enabled = !importing,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing()
                    .semantics { contentDescription = "Добавить подписку или ключ" },
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer),
            ) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Добавить подписку или ключ", fontSize = 13.sp)
            }
            if (onTvPair != null) {
                FilledTonalButton(
                    onClick = onTvPair,
                    enabled = !importing,
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusRing()
                        .semantics { contentDescription = "Показать QR-код для передачи с телефона" },
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(Icons.Default.QrCode, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("QR с телефона", fontSize = 13.sp)
                }
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

/** Remote subscriptions always precede individual keys, on phone, tablet and TV.
 * Pinning only changes the order inside its own category.
 */
internal fun orderedSubscriptions(
    subscriptions: List<Subscription>,
    pinnedId: String?,
): List<Subscription> = subscriptions.sortedWith(
    compareByDescending<Subscription> {
        it.source.startsWith("https://", ignoreCase = true) ||
            it.source.startsWith("http://", ignoreCase = true)
    }.thenByDescending { it.id == pinnedId }
)

/** Identical subscription titles are distinguished without exposing private URLs. */
internal fun subscriptionDisplayName(owner: Subscription, all: List<Subscription>): String {
    // A user-given name wins over the provider's Profile-Title. Do not give
    // Phantom or any other provider a special label or presentation.
    fun label(subscription: Subscription): String =
        subscription.customTitle?.trim()?.takeIf(String::isNotEmpty)
            ?: subscription.title.trim()
    val title = label(owner)
    val duplicates = all.filter { label(it).equals(title, ignoreCase = true) }
    return if (duplicates.size <= 1) title
        else "$title · ${duplicates.indexOfFirst { it.id == owner.id } + 1}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServersPage(
    subscriptions: List<Subscription>, selected: Pair<Subscription, ServerProfile>?,
    pinnedId: String?, favorites: Set<String>, latency: Map<String, Long>,
    probing: Set<String>, measuring: Map<String, String>, refreshing: Set<String>, failed: Set<String>,
    onRefresh: (Subscription) -> Unit, manage: (Subscription) -> Unit,
    onAutoProbe: (Subscription) -> Unit, onManualPing: (Subscription) -> Unit,
    favorite: (String) -> Unit,
    choose: (Subscription, ServerProfile) -> Unit, openUrl: (String) -> Unit,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    listState: LazyListState? = null,
) {
    val expansion = rememberSubscriptionExpansionStore()
    var details by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var inspectedServer by remember { mutableStateOf<ServerProfile?>(null) }
    val groups = remember(subscriptions, pinnedId, favorites) {
        orderedSubscriptions(subscriptions, pinnedId).map { owner ->
            owner to owner.servers.sortedWith(compareByDescending<ServerProfile>(ProfileSelection::isProviderAutomatic)
                .thenByDescending { "${owner.id}:${it.id}" in favorites })
        }
    }
    val c = MaterialTheme.colorScheme
    val tv = isTelevision()
    val ownListState = rememberLazyListState()
    LazyColumn(
        state = listState ?: ownListState,
        contentPadding = PaddingValues(horizontal = if (tv) 8.dp else 16.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) item(key = "combined-home") { header() }
        if (groups.isEmpty()) item {
            Text("Добавьте подписку или ключ, чтобы увидеть серверы", Modifier.padding(12.dp))
        }
        groups.forEach { (owner, servers) ->
            val remote = owner.source.startsWith("https://", true) || owner.source.startsWith("http://", true)
            val single = !remote && servers.size == 1
            val defaultExpanded = owner.id == (
                selected?.first?.id ?: pinnedId ?: subscriptions.firstOrNull()?.id
            )
            val show = expansion.isExpanded(owner.id, defaultExpanded)
            val name = subscriptionDisplayName(owner, subscriptions)
            if (!single) item(key = "header:${owner.id}") {
                Column(Modifier.groupSurface(first = true, last = !show || servers.isEmpty())) {
                    // Title and actions share ONLY the top row. The update
                    // timestamp gets its own full-width line instead of wrapping
                    // into the ping/refresh touch targets on narrow phones.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = if (tv) 46.dp else 48.dp)
                            .tvFocusRing().clickable {
                                expansion.setExpanded(owner.id, !show)
                            }.semantics {
                                contentDescription = (if (show) "Свернуть" else "Раскрыть") + " подписку " + name
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (show) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                null, Modifier.padding(horizontal = 6.dp).size(18.dp), tint = c.primary)
                            Text(name, modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp, lineHeight = 17.sp)
                        }
                        // Keep identical action order on phone, tablet and TV.
                        if (remote) IconButton(onClick = { onRefresh(owner) },
                            enabled = owner.id !in refreshing,
                            modifier = Modifier.tvFocusRing().semantics {
                                contentDescription = "Обновить подписку $name"
                            }) {
                            if (owner.id in refreshing)
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, null, tint = c.primary)
                        }
                        if (servers.any { ProtocolSupport.isSupported(it.scheme) }) {
                            IconButton(onClick = { onManualPing(owner) },
                                enabled = owner.id !in probing,
                                modifier = Modifier.tvFocusRing().semantics {
                                    contentDescription = "Проверить пинг $name"
                                }) {
                                if (owner.id in probing)
                                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Speed, null,
                                    Modifier.size(20.dp), tint = c.primary)
                            }
                        }
                        OwnerMenu(name) { manage(owner) }
                    }
                    if (remote) {
                        val time = android.text.format.DateFormat.getTimeFormat(
                            androidx.compose.ui.platform.LocalContext.current).format(java.util.Date(owner.updatedAt))
                        val interval = intervalLabel(owner.customIntervalSeconds ?: owner.updateIntervalSeconds)
                        Text(if (owner.updatedAt > 0) "Обновлено $time · $interval" else interval,
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 35.dp, end = 12.dp, bottom = 4.dp),
                            color = c.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (owner.id in failed) Text("Ошибка обновления · серверы сохранены",
                            modifier = Modifier.padding(start = 35.dp, bottom = 3.dp),
                            color = c.error, fontSize = 11.sp)
                        ProviderInfo(owner, name, owner.id in details, {
                            details = ArrayList(if (owner.id in details) details - owner.id else details + owner.id)
                        }, openUrl)
                    }
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
                        Row(Modifier.weight(1f).heightIn(min = if (tv) 48.dp else 54.dp).tvFocusRing().combinedClickable(
                                onClick = { choose(owner, server) },
                                onLongClickLabel = "Открыть JSON конфигурации",
                                onLongClick = { inspectedServer = server },
                            )
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
                        if (isTelevision()) {
                            IconButton(
                                onClick = { inspectedServer = server },
                                modifier = Modifier.tvFocusRing().semantics {
                                    contentDescription = "Открыть JSON конфигурации ${displayServerName(server)}"
                                }
                            ) { Icon(Icons.Default.Code, null, Modifier.size(19.dp)) }
                        }
                        if (single && ProtocolSupport.isSupported(server.scheme)) {
                            IconButton(onClick = { onManualPing(owner) },
                                enabled = owner.id !in probing,
                                modifier = Modifier.tvFocusRing().semantics {
                                    contentDescription = "Проверить пинг $name"
                                }) {
                                if (owner.id in probing) CircularProgressIndicator(
                                    Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Speed, null,
                                    Modifier.size(20.dp), tint = c.primary)
                            }
                        }
                        if (single) OwnerMenu(name) { manage(owner) }
                    }
                    if (single && owner.id !in probing) LaunchedEffect(owner.id, server.raw) { onAutoProbe(owner) }
                }
            }
            item(key = "gap:${owner.id}") { Spacer(Modifier.height(6.dp)) }
        }
        if (footer != null) item(key = "combined-footer") { footer() }
     }
    inspectedServer?.let { selectedServer ->
        ServerConfigSheet(selectedServer) { inspectedServer = null }
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
internal fun ProviderInfo(sub: Subscription, displayName: String, details: Boolean, toggle: () -> Unit, openUrl: (String) -> Unit) {
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
    // Keep the entire owner summary and its detail action in ONE metadata row.
    // All remote providers use the same layout; no provider-specific shortcut.
    Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 0.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (account != null) IconButton(
                onClick = { openUrl(account) }, modifier = Modifier.size(38.dp).tvFocusRing()
            ) {
                Icon(Icons.Default.Info, "Кабинет $displayName",
                    Modifier.size(18.dp), tint = c.primary)
            }
            if (traffic != null || limit != null) Column(Modifier.weight(1f)) {
                Caption(if (traffic == null) "Лимит" else "Трафик")
                Text(listOfNotNull(traffic, limit).joinToString(" / ") {
                    it.replace(" ", "\u00A0")
                }, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2)
            } else Spacer(Modifier.weight(1f))
            if (expiry != null) {
                // The whole date/details column is a 48+ dp touch target.
                // No redundant third row just to show "Подробнее".
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .tvFocusRing()
                        .clickable(onClick = toggle)
                        .semantics { contentDescription = "Подробнее о $displayName" },
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Caption(if (expiry == 0L) "Срок" else
                        "До · ${daysLeft(expiry, System.currentTimeMillis() / 1000)} дн.")
                    Text(if (expiry == 0L) "Бессрочно" else dateLabel(expiry),
                        fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (details) "Меньше" else "Подробнее",
                            fontSize = 11.sp, color = c.primary, maxLines = 1)
                        Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, Modifier.size(14.dp), tint = c.primary)
                    }
                }
            } else {
                TextButton(onClick = toggle,
                    contentPadding = PaddingValues(horizontal = 5.dp),
                    modifier = Modifier.weight(1f).tvFocusRing().semantics {
                        contentDescription = "Подробнее о $displayName"
                    }) {
                    Text(if (details) "Меньше" else "Подробнее", fontSize = 11.sp)
                    Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(15.dp))
                }
            }
            if (support != null) IconButton(
                onClick = { openUrl(support) }, modifier = Modifier.size(38.dp).tvFocusRing()
            ) {
                val host = android.net.Uri.parse(support).host?.lowercase()
                Icon(if (host in setOf("t.me", "telegram.me"))
                    Icons.Default.Send else Icons.Default.SupportAgent,
                    "Поддержка $displayName", Modifier.size(18.dp), tint = c.primary)
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
    transferMode: String = "",
    onLater: () -> Unit,
    onUpdate: () -> Unit,
    onDownloadInBrowser: () -> Unit,
    onCancelDownload: () -> Unit,
    signingMismatch: Boolean = false,
    onOpenBackup: () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    val brief = remember(release.notes) { ReleaseNotes.summary(release.notes, 3) }
    val details = remember(release.notes) { ReleaseNotes.summary(release.notes, 15) }
    var expanded by rememberSaveable(release.version) { mutableStateOf(false) }
    val downloadInProgress by rememberUpdatedState(downloading)
    val tv = isTelevision()
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp *
        (if (tv) 0.80f else 0.82f)).coerceAtLeast(280.dp)
    val blocked = signingMismatch ||
        error?.startsWith("Подпись обновления не совпадает") == true
    val sheetBody: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .padding(horizontal = if (tv) 22.dp else 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdateAlt, null, tint = c.primary,
                    modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Обновление Phantom",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Caption((if (release.isBeta) "Бета-версия " else "Версия ") +
                        release.version)
                }
            }
            if (release.delta != null && !readyToInstall) {
                val deltaMb = release.delta.sizeBytes.toDouble() / 1_048_576.0
                val fullMb = release.sizeBytes.toDouble() / 1_048_576.0
                Caption("Экономное обновление: ≈" +
                    String.format(java.util.Locale.US, "%.1f", deltaMb) + " МБ вместо " +
                    String.format(java.util.Locale.US, "%.0f", fullMb) +
                    " МБ; при необходимости загрузим полный APK")
            }
            if (brief.isNotBlank()) {
                Text(if (expanded) details else brief,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    fontSize = 13.sp, lineHeight = 18.sp,
                    color = c.onSurfaceVariant)
                if (brief != details) TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End).tvFocusRing()) {
                    Text(if (expanded) "Меньше" else "Ещё")
                }
            }
            if (downloading) {
                LinearProgressIndicator(progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth())
                Caption((transferMode.ifBlank { "Загрузка обновления" }) +
                    " · $progress%" + if (speedBytesPerSecond > 0L)
                    " · " + speedBytesPerSecond / 1024L + " КБ/с" else "")
            }
            if (blocked) {
                Surface(color = c.errorContainer.copy(alpha = .45f),
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        Text("Другая подпись приложения",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = c.onErrorContainer)
                        Spacer(Modifier.height(5.dp))
                        Text("Android не разрешит обновление поверх этой копии. " +
                            "Сначала сохраните подписки и настройки в резервную копию. " +
                            "Не удаляйте Phantom до сохранения файла.",
                            fontSize = 12.sp, lineHeight = 17.sp,
                            color = c.onErrorContainer)
                        TextButton(onClick = onOpenBackup, modifier = Modifier.tvFocusRing()) {
                            Text("Резервная копия")
                        }
                    }
                }
            } else error?.let {
                Text(it, color = c.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!downloading && !blocked && error != null) TextButton(
                onClick = onDownloadInBrowser,
                modifier = Modifier.align(Alignment.Start).tvFocusRing()
            ) {
                Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Скачать через браузер")
            }
            val shape = RoundedCornerShape(14.dp)
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { if (downloading) onCancelDownload() else onLater() },
                    shape = shape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = c.surfaceVariant, contentColor = c.onSurface),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(shape)
                ) { Text(if (downloading) "Остановить" else "Позже") }
                Button(
                    onClick = onUpdate, enabled = !downloading && !blocked,
                    shape = shape,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).tvFocusRing(shape)
                ) {
                    Text(when {
                        blocked -> "Недоступно"
                        downloading -> "Скачивание…"
                        readyToInstall -> "Установить"
                        else -> "Обновить"
                    })
                }
            }
        }
    }
    if (tv) {
        Dialog(onDismissRequest = { if (!downloading) onLater() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.fillMaxWidth(.72f).widthIn(max = 660.dp)
                    .heightIn(max = maxHeight),
                shape = RoundedCornerShape(22.dp),
                color = c.surface
            ) { sheetBody() }
        }
    } else ModalBottomSheet(
        onDismissRequest = { if (!downloading) onLater() },
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !downloadInProgress }),
    ) { sheetBody() }
}

internal fun daysLeft(expiry: Long, now: Long): Long =
    if (expiry <= now) 0L else (expiry - now - 1L) / 86400L + 1L


/**
 * Provider-reported metadata only. Unknown expiry or traffic must never be
 * mistaken for an active paid plan; checkout belongs to the official store.
 */
internal data class SubscriptionAccessSnapshot(
    val daysRemaining: Long?,
    val usedBytes: Long?,
    val limitBytes: Long?,
    val progress: Float?,
)

internal fun subscriptionAccessSnapshot(sub: Subscription, nowSeconds: Long): SubscriptionAccessSnapshot {
    val days = sub.expiresAt?.takeIf { it > 0L }?.let { daysLeft(it, nowSeconds) }
    val uploaded = sub.uploadBytes?.coerceAtLeast(0L)
    val downloaded = sub.downloadBytes?.coerceAtLeast(0L)
    val used = when {
        uploaded != null && downloaded != null ->
            if (Long.MAX_VALUE - uploaded < downloaded) Long.MAX_VALUE else uploaded + downloaded
        else -> uploaded ?: downloaded
    }
    val limit = sub.totalBytes?.takeIf { it > 0L }
    val progress = if (limit != null && used != null)
        (used.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0).toFloat()
    else null
    return SubscriptionAccessSnapshot(days, used, limit, progress)
}

@Composable
private fun SubscriptionAccessSummary(sub: Subscription) {
    val colors = MaterialTheme.colorScheme
    val tv = isTelevision()
    val access = subscriptionAccessSnapshot(sub, System.currentTimeMillis() / 1000L)
    val status = when (access.daysRemaining) {
        null -> "Срок не указан"
        0L -> "Срок истёк"
        else -> "Осталось ${access.daysRemaining} дн."
    }
    val date = sub.expiresAt?.takeIf { it in 1L..253402300799L }?.let {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale("ru", "RU"))
            .format(java.util.Date(it * 1000L))
    }
    val limit = when (sub.totalBytes) {
        null -> "Не указан"
        0L -> "Без лимита"
        else -> access.limitBytes?.let(::sizeLabel) ?: "Не указан"
    }
    Surface(
        modifier = if (tv) Modifier.tvFocusRing(RoundedCornerShape(16.dp))
            .focusable() else Modifier,
        color = colors.surfaceVariant.copy(alpha = .37f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.outline.copy(alpha = .2f))
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Text("ВЫБРАННАЯ ПОДПИСКА", fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                color = colors.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            Text(sub.customTitle?.takeIf { it.isNotBlank() } ?: sub.title,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = if (tv) 8 else 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = colors.outline.copy(alpha = .2f))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Column(Modifier.weight(1f)) {
                    Caption("СРОК")
                    Spacer(Modifier.height(4.dp))
                    Text(status, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = if (access.daysRemaining == 0L) colors.error else colors.onSurface)
                    date?.let { Caption("До $it") }
                }
                Column(Modifier.weight(1f)) {
                    Caption("ТРАФИК")
                    Spacer(Modifier.height(4.dp))
                    Text(access.usedBytes?.let(::sizeLabel)?.replace(" ", "\u00A0") ?: "—",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Caption("Лимит: $limit")
                }
            }
            access.progress?.let { fraction ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(6.dp)),
                    color = colors.primary, trackColor = colors.outline.copy(alpha = .16f))
            }
            Spacer(Modifier.height(6.dp))
            Caption("Данные получены от сервера подписки")
        }
    }
}

@Composable
internal fun SubscriptionPage(currentSubscription: Subscription? = null, openShop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (isTelevision()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.primary.copy(alpha = .36f))
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ваш доступ", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    if (currentSubscription != null) {
                        SubscriptionAccessSummary(currentSubscription)
                    } else {
                        Text("Подписка не выбрана. Добавьте свою ссылку или оформите доступ.",
                            color = colors.onSurfaceVariant)
                    }
                    HorizontalDivider(color = colors.outline.copy(alpha = .25f))
                    Text("Покупка и продление — в официальном магазине.",
                        color = colors.onSurfaceVariant)
                    Button(
                        onClick = openShop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).tvFocusRing(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("В магазин")
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Default.NorthEast, null, Modifier.size(19.dp))
                    }
                    Caption("Продлить: в магазине «Моя подписка» → «Продлить».")
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 5.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.primary.copy(alpha = .30f))
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null, tint = colors.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Ваш доступ", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.padding(11.dp)) {
                    if (currentSubscription != null) {
                        SubscriptionAccessSummary(currentSubscription)
                    } else {
                        Surface(color = colors.surfaceVariant.copy(alpha = .30f),
                            shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(13.dp)) {
                                Text("Подписка не выбрана", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("Добавьте подписку на главной странице или оформите доступ.",
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                    Button(onClick = openShop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).tvFocusRing(),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF64AF8E), contentColor = Color(0xFF0A241A))) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("В магазин", fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.NorthEast, null, Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Caption("Продлить: «Моя подписка» → «Продлить» в магазине.")
                }
            }
        }
    }
}
