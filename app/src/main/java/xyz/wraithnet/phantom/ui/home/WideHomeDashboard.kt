package xyz.wraithnet.phantom.ui.home

import androidx.compose.animation.animateColorAsState
import xyz.wraithnet.phantom.ui.theme.powerCenter
import xyz.wraithnet.phantom.ui.theme.powerMiddle
import xyz.wraithnet.phantom.ui.theme.powerEdge
import xyz.wraithnet.phantom.ui.theme.powerInteriorBrush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xyz.wraithnet.phantom.data.ExitNetworkLookup
import xyz.wraithnet.phantom.data.CountryFlags
import xyz.wraithnet.phantom.data.ProfileSelection
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionPingText

/**
 * Real TV/tablet Home. A SINGLE lazy vertical scroller owns connection, every
 * subscription group, two-column server rows and import/QR at the bottom.
 * The phone Home and existing ServersPage are never altered by this layout.
 */
@Composable
internal fun WideHomeDashboard(
    status: String,
    selected: Pair<Subscription, ServerProfile>?,
    download: Long,
    upload: Long,
    seconds: Long,
    subscriptions: List<Subscription>,
    pinnedId: String?,
    favorites: Set<String>,
    latency: Map<String, Long>,
    measuring: Map<String, String>,
    refreshing: Set<String>,
    probing: Set<String>,
    failed: Set<String>,
    importing: Boolean,
    focusEpoch: Int,
    modalVisible: Boolean,
    onToggle: () -> Unit,
    onSelect: (Subscription, ServerProfile) -> Unit,
    onRefresh: (Subscription) -> Unit,
    onManage: (Subscription) -> Unit,
    onProbe: (Subscription) -> Unit,
    onManualPing: (Subscription) -> Unit,
    onFavorite: (String) -> Unit,
    onImport: () -> Unit,
    onPairQr: (() -> Unit)?,
    onScanQr: (() -> Unit)?,
    onShowError: (() -> Unit)?,
    onOpenUrl: (String) -> Unit,
    exit: ExitNetworkLookup.Info? = null,
    checkingExit: Boolean = false,
    openExit: () -> Unit = {},
) {
    val tv = isTelevision()
    val scheme = MaterialTheme.colorScheme
    // Wide TV uses two columns for a D-pad; touch tablets use a single
    // readable full-width list. Three/two columns on tablets were confusing.
    val gridColumns = if (tv) 2 else 1
    val scroll = rememberLazyListState()
    val powerFocus = remember { FocusRequester() }
    val hostView = androidx.compose.ui.platform.LocalView.current
    var powerFocused by remember { mutableStateOf(false) }
    var selectedForJson by remember { mutableStateOf<ServerProfile?>(null) }
    val expansion = rememberSubscriptionExpansionStore()
    var showDetails by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val sortedGroups = remember(subscriptions, pinnedId, selected?.first?.id, favorites) {
        orderedSubscriptions(subscriptions, pinnedId, selected?.first?.id).map { owner ->
            owner to owner.servers.sortedWith(
                compareByDescending<ServerProfile>(ProfileSelection::isProviderAutomatic)
                    .thenByDescending { (owner.id + ":" + it.id) in favorites })
        }
    }
    // After returning from pairing or import restore a real remote focus target.
    LaunchedEffect(tv, focusEpoch, modalVisible) {
        if (tv && !modalVisible) {
            // Cold Android TV startup and dialog dismissal can attach the
            // Compose focus target before the Activity window gains focus.
            // Keep trying until the power target actually owns DPAD_CENTER,
            // instead of stopping after a fixed 450 ms regardless of focus.
            repeat(36) {
                withFrameNanos { }
                if (hostView.hasWindowFocus()) {
                    hostView.requestFocus()
                    runCatching { powerFocus.requestFocus() }
                    withFrameNanos { }
                    if (powerFocused) return@LaunchedEffect
                }
                delay(125)
            }
        }
    }
    // Tablet uses the phone's continuous subscription surfaces, but the
    // scroller stays lazy even with hundreds of servers. Cap content width,
    // not the window, so landscape does not stretch text across the screen.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        state = scroll,
        modifier = if (tv) Modifier.fillMaxSize()
            else Modifier.widthIn(max = 840.dp).fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = if (tv) 12.dp else 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 9.dp else 0.dp)
    ) {
        item(key = "wide-home-status") {
            if (!tv) {
                Text("Phantom", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 5.dp))
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surface,
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = .22f))
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                    val active = status == "connected" || status == "degraded"
                    val verified = status == "connected"
                    val connecting = status == "starting"
                    val center by animateColorAsState(scheme.powerCenter(verified), label = "wide power center")
                    val middle by animateColorAsState(scheme.powerMiddle(verified), label = "wide power middle")
                    val edge by animateColorAsState(scheme.powerEdge(verified), label = "wide power edge")
                    val ring = when {
                        verified -> scheme.primary
                        active -> scheme.tertiary.copy(alpha = .72f)
                        else -> scheme.outline.copy(alpha = .72f)
                    }
                    Box(
                        modifier = Modifier.size(if (tv) 112.dp else 106.dp)
                            .focusRequester(powerFocus)
                            .onFocusChanged { powerFocused = it.isFocused }
                            .tvFocusRing(CircleShape)
                            .clip(CircleShape)
                            .background(powerInteriorBrush(center, middle, edge))
                            // Never drop D-pad focus during the VPN handshake.
                            // An inactive click while starting is harmless; removing
                            // focusability moves OK onto an unrelated TV control.
                            .clickable(enabled = tv || !connecting) {
                                if (!connecting) onToggle()
                            }
                            .semantics {
                                contentDescription = if (active) "Отключить VPN"
                                    else if (connecting) "Подключение"
                                    else "Подключить VPN"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(2.dp, ring)
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (connecting) {
                                    CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.PowerSettingsNew, null,
                                        modifier = Modifier.size(28.dp),
                                        tint = if (verified) scheme.onSurface else scheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(when (status) {
                                    "connected" -> "Подключено"
                                    "degraded" -> if (onShowError == null) "Проверка…" else "Нет проверки"
                                    "starting" -> "Подключение"
                                    "error" -> "Ошибка"
                                    else -> "Отключено"
                                }, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                                    maxLines = 1, color = scheme.onSurface)
                                // Uptime appears ONLY in the power circle.
                                if (active) Text(
                                    String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                                        seconds / 3600, seconds / 60 % 60, seconds % 60),
                                    fontSize = 11.sp, color = scheme.onSurface,
                                    maxLines = 1)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            selected?.second?.let {
                                Text(CountryFlags.flag(it), fontSize = 28.sp)
                                Spacer(Modifier.width(9.dp))
                            }
                            Text(selected?.second?.let(::displayServerName) ?: "Выбрать сервер",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = if (tv) 3 else 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(selected?.second?.let(::protocolLabel) ?: "Нет выбранного сервера",
                            color = scheme.onSurfaceVariant, fontSize = 12.sp,
                            maxLines = if (tv) 3 else 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                            if (download >= 0) Text("↓ " + sizeLabel(download),
                                fontSize = 11.sp, color = scheme.onSurfaceVariant)
                            if (upload >= 0) Text("↑ " + sizeLabel(upload),
                                fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        }
                        if (status in setOf("error", "degraded") && onShowError != null)
                            TextButton(onClick = onShowError, modifier = Modifier.tvFocusRing()) {
                                Text("Причина ошибки")
                            }
                    }
                    if (!tv && (exit != null || checkingExit)) {
                        Column(Modifier.widthIn(max = 180.dp)
                            .clickable(enabled = exit != null, onClick = openExit),
                            horizontalAlignment = Alignment.End) {
                            if (exit != null) {
                                Text(exit.flag() + " " + exit.countryName(),
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = scheme.onSurfaceVariant)
                                Text(exit.network.split(" trading as ", ignoreCase = true).first(),
                                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = scheme.onSurfaceVariant)
                                Text(listOf(exit.ip, exit.asn).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 10.sp, maxLines = 2, color = scheme.onSurfaceVariant)
                            } else Text("Определение сети…", fontSize = 10.sp,
                                color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (!tv) item(key = "tablet-status-gap") { Spacer(Modifier.height(12.dp)) }
        // Selected country and subscription already identify the server list.
        // Keep the wide layout clean without a count or redundant heading.
        if (sortedGroups.isEmpty()) item(key = "wide-empty") {
            Surface(
                color = scheme.surfaceVariant.copy(alpha = .38f),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text("Добавьте подписку или ключ, чтобы появились серверы.",
                    modifier = Modifier.fillMaxWidth().padding(17.dp),
                    color = scheme.onSurfaceVariant)
            }
        }
        sortedGroups.forEach { (owner, profiles) ->
            val title = subscriptionDisplayName(owner, subscriptions)
            val visible = expansion.isExpanded(owner.id, defaultExpanded = true)
            val remote = owner.source.startsWith("https://", true) ||
                owner.source.startsWith("http://", true)
            item(key = "wide-group:" + owner.id) {
                Surface(
                    modifier = if (tv) Modifier else Modifier.fillMaxWidth()
                        .testTag("tablet-subscription-header:" + owner.id)
                        .groupSurface(first = true, last = !visible || profiles.isEmpty()),
                    shape = if (tv) RoundedCornerShape(14.dp) else RectangleShape,
                    color = if (tv) scheme.surface else Color.Transparent,
                    border = if (tv) BorderStroke(1.dp, scheme.outline.copy(alpha = .22f)) else null
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.weight(1f)
                                    .heightIn(min = 46.dp)
                                    .tvFocusRing()
                                    .clickable {
                                        expansion.setExpanded(owner.id, !visible)
                                    }
                                    .semantics {
                                        contentDescription = (if (visible) "Свернуть" else "Раскрыть") +
                                            " подписку " + title
                                    }
                                    .padding(start = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (visible) Icons.Default.ExpandMore
                                    else Icons.Default.ChevronRight, null,
                                    modifier = Modifier.size(20.dp), tint = scheme.primary)
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontWeight = FontWeight.SemiBold,
                                        maxLines = if (tv) 4 else 2, overflow = TextOverflow.Ellipsis,
                                        fontSize = if (tv) 14.sp else 15.sp)
                                    if (remote) {
                                        val interval = intervalLabel(
                                            owner.customIntervalSeconds ?: owner.updateIntervalSeconds)
                                        val label = if (tv || owner.updatedAt <= 0) "Обновление · " + interval
                                            else "Обновлено " + android.text.format.DateFormat.getTimeFormat(
                                                androidx.compose.ui.platform.LocalContext.current
                                            ).format(java.util.Date(owner.updatedAt)) + " · " + interval
                                        Text(label, color = scheme.onSurfaceVariant,
                                            fontSize = if (tv) 10.sp else 11.sp,
                                            maxLines = if (tv) 2 else 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (owner.id == pinnedId) Icon(Icons.Default.PushPin,
                                    if (remote) "Подписка закреплена" else "Ключ закреплён",
                                    Modifier.padding(end = 4.dp).size(14.dp), tint = scheme.primary)
                            }
                            if (remote) IconButton(
                                onClick = { onRefresh(owner) },
                                enabled = owner.id !in refreshing,
                                modifier = Modifier.tvFocusRing()
                                    .semantics { contentDescription = "Обновить подписку " + title }
                            ) {
                                if (owner.id in refreshing)
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, null)
                            }
                            IconButton(
                                onClick = { onManualPing(owner) },
                                enabled = owner.id !in probing,
                                modifier = Modifier.tvFocusRing()
                                    .semantics { contentDescription = "Проверить пинг " + title }
                            ) { Icon(Icons.Default.Speed, null, Modifier.size(19.dp)) }
                            IconButton(onClick = { onManage(owner) },
                                modifier = Modifier.tvFocusRing()
                                    .semantics { contentDescription = "Управление подпиской " + title }
                            ) { Icon(Icons.Default.MoreVert, null) }
                        }
                        if (owner.id in failed) Text(
                            "Ошибка обновления · серверы сохранены",
                            modifier = Modifier.padding(horizontal = 14.dp),
                            fontSize = 11.sp, color = scheme.error)
                        val used = owner.uploadBytes?.let { up ->
                            owner.downloadBytes?.let { down ->
                                if (up <= Long.MAX_VALUE - down) up + down else Long.MAX_VALUE
                            } ?: up
                        } ?: owner.downloadBytes
                        val traffic = used?.let(::sizeLabel) ?: "—"
                        val total = owner.totalBytes?.let {
                            if (it == 0L) "∞" else sizeLabel(it)
                        } ?: "—"
                        val expires = owner.expiresAt?.let {
                            if (it == 0L) "Бессрочно" else dateLabel(it)
                        } ?: "—"
                        if (remote && !tv) {
                            ProviderInfo(owner, title, owner.id in showDetails, {
                                showDetails = ArrayList(if (owner.id in showDetails) showDetails - owner.id
                                    else showDetails + owner.id)
                            }, onOpenUrl)
                        }
                        if (remote && tv) Row(
                            Modifier.fillMaxWidth().padding(start = 13.dp, end = 8.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Трафик " + traffic + " / " + total + "   ·   До " + expires,
                                fontSize = 10.sp, maxLines = if (tv) 4 else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = scheme.onSurfaceVariant)
                            TextButton(
                                onClick = {
                                    showDetails = ArrayList(
                                        if (owner.id in showDetails) showDetails - owner.id
                                        else showDetails + owner.id)
                                },
                                modifier = Modifier.tvFocusRing()
                            ) { Text("Подробнее", fontSize = 11.sp) }
                        }
                        if (tv && owner.id in showDetails && remote) {
                            ProviderInfo(owner, title, true, {
                                showDetails = ArrayList(showDetails - owner.id)
                            }, onOpenUrl)
                        }
                    }
                }
                if (visible && owner.id !in probing) {
                    LaunchedEffect(owner.id, owner.servers) { onProbe(owner) }
                }
            }
            if (tv && visible && profiles.isNotEmpty()) item(key = "wide-group-divider:" + owner.id) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 2.dp),
                    thickness = 1.dp,
                    color = scheme.outline.copy(alpha = .28f)
                )
            }
            if (visible) {
                profiles.chunked(gridColumns).forEachIndexed { index, pair ->
                    item(key = if (tv) "wide-row:" + owner.id + ":" + index
                        else "tablet-row:" + owner.id + ":" + pair.first().id) {
                        Column(if (tv) Modifier.fillMaxWidth() else Modifier.fillMaxWidth()
                            .testTag("tablet-subscription-row:" + owner.id + ":" + pair.first().id)
                            .groupSurface(first = false, last = index == profiles.lastIndex)) {
                        if (!tv) HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = scheme.outline.copy(alpha = .22f))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            pair.forEach { server ->
                                val id = owner.id + ":" + server.id
                                val active = selected?.first?.id == owner.id &&
                                    selected?.second?.id == server.id
                                val shape = RoundedCornerShape(13.dp)
                                Surface(
                                    shape = if (tv) shape else RectangleShape,
                                    border = if (tv) BorderStroke(if (active) 2.dp else 1.dp,
                                        if (active) scheme.primary.copy(alpha = .7f)
                                        else scheme.outline.copy(alpha = .24f)) else null,
                                    color = if (active) scheme.primaryContainer.copy(alpha = .40f)
                                        else if (tv) scheme.surface else Color.Transparent,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = if (tv) 59.dp else 58.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Row(
                                            Modifier.weight(1f)
                                                .tvFocusRing(shape)
                                                .clickable { onSelect(owner, server) }
                                                .semantics {
                                                    contentDescription =
                                                        "Выбрать сервер " + displayServerName(server)
                                                }
                                                .padding(start = 11.dp, top = 7.dp, bottom = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(CountryFlags.flag(server), fontSize = 21.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(displayServerName(server),
                                                    fontSize = if (tv) 13.sp else 14.sp,
                                                    fontWeight = if (active) FontWeight.SemiBold
                                                        else FontWeight.Medium,
                                                    maxLines = if (tv) 4 else 1,
                                                    overflow = TextOverflow.Ellipsis)
                                                Text(protocolLabel(server),
                                                    fontSize = 10.sp,
                                                    maxLines = if (tv) 3 else 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = scheme.onSurfaceVariant)
                                                if (tv) Text(SubscriptionPingText.display(
                                                    latency[id], measuring[id] == server.raw),
                                                    fontSize = 10.sp,
                                                    color = scheme.onSurfaceVariant)
                                            }
                                            if (!tv) Text(SubscriptionPingText.display(
                                                latency[id], measuring[id] == server.raw),
                                                modifier = Modifier.padding(horizontal = 10.dp),
                                                fontSize = 12.sp, color = scheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { onFavorite(id) },
                                            modifier = Modifier.size(36.dp).tvFocusRing()) {
                                            Icon(if (id in favorites) Icons.Default.Star
                                                else Icons.Default.StarBorder,
                                                if (id in favorites) "Убрать из избранного"
                                                else "В избранное",
                                                Modifier.size(18.dp),
                                                tint = if (id in favorites) Color(0xFFCEB36D)
                                                    else scheme.onSurfaceVariant)
                                        }
                                        if (tv) IconButton(
                                            onClick = { selectedForJson = server },
                                            modifier = Modifier.size(35.dp).tvFocusRing()
                                        ) {
                                            Icon(Icons.Default.Code,
                                                "Открыть JSON конфигурации " + displayServerName(server),
                                                Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                            repeat(gridColumns - pair.size) { Spacer(Modifier.weight(1f)) }
                        }
                        }
                    }
                }
            }
            if (!tv) item(key = "tablet-gap:" + owner.id) { Spacer(Modifier.height(12.dp)) }
        }
        item(key = "wide-import-and-qr") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
                Text("Добавление конфигурации",
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 7.dp))
                val actionShape = RoundedCornerShape(13.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    // Material3's default FilledTonalButton uses neutral
                    // secondaryContainer: it looks DISABLED in Forest mode.
                    // Use the actual chosen theme palette for both tablet/TV CTAs.
                    Button(
                        onClick = onImport,
                        enabled = !importing,
                        shape = actionShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary,
                            disabledContainerColor = scheme.surfaceVariant,
                            disabledContentColor = scheme.onSurfaceVariant),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                            .tvFocusRing(actionShape)
                            .semantics { contentDescription = "Добавить подписку или ключ" }
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить подписку или ключ", maxLines = if (tv) 3 else 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    val qrAction = onPairQr ?: onScanQr
                    if (qrAction != null) FilledTonalButton(
                        onClick = qrAction,
                        enabled = !importing,
                        shape = actionShape,
                        border = BorderStroke(1.dp, scheme.primary.copy(alpha = .55f)),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                            disabledContainerColor = scheme.surfaceVariant,
                            disabledContentColor = scheme.onSurfaceVariant),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                            .tvFocusRing(actionShape)
                            .semantics {
                                contentDescription = if (tv) "QR с телефона" else "Сканировать QR"
                            }
                    ) {
                        Icon(Icons.Default.QrCode, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (tv) "QR с телефона" else "Сканировать QR",
                            maxLines = if (tv) 3 else 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    }
    selectedForJson?.let { profile ->
        ServerConfigSheet(profile) { selectedForJson = null }
    }
}
