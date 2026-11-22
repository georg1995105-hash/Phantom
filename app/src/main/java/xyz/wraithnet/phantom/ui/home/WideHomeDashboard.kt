package xyz.wraithnet.phantom.ui.home

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
) {
    val tv = isTelevision()
    val scheme = MaterialTheme.colorScheme
    // Wide TV uses two columns for a D-pad; touch tablets use a single
    // readable full-width list. Three/two columns on tablets were confusing.
    val gridColumns = if (tv) 2 else 1
    val scroll = rememberLazyListState()
    val powerFocus = remember { FocusRequester() }
    var selectedForJson by remember { mutableStateOf<ServerProfile?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var showDetails by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val sortedGroups = remember(subscriptions, pinnedId, favorites) {
        orderedSubscriptions(subscriptions, pinnedId).map { owner ->
            owner to owner.servers.sortedWith(
                compareByDescending<ServerProfile>(ProfileSelection::isProviderAutomatic)
                    .thenByDescending { (owner.id + ":" + it.id) in favorites })
        }
    }
    // After returning from pairing or import restore a real remote focus target.
    LaunchedEffect(tv, focusEpoch, modalVisible) {
        if (tv && !modalVisible) {
            repeat(6) {
                withFrameNanos { }
                runCatching { powerFocus.requestFocus() }
                delay(75)
            }
        }
    }
    LazyColumn(
        state = scroll,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = if (tv) 12.dp else 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
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
                    val active = status == "connected"
                    val connecting = status == "starting"
                    val ring = if (active) scheme.primary.copy(alpha = .72f)
                        else scheme.outline.copy(alpha = .72f)
                    Box(
                        modifier = Modifier.size(if (tv) 112.dp else 106.dp)
                            .focusRequester(powerFocus)
                            .tvFocusRing(CircleShape)
                            .clip(CircleShape)
                            .background(if (active) scheme.primaryContainer.copy(alpha = .48f)
                                else scheme.surfaceVariant.copy(alpha = .58f))
                            .clickable(enabled = !connecting) { onToggle() }
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
                                        tint = if (active) scheme.primary else scheme.onSurface)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(when (status) {
                                    "connected" -> "Подключено"
                                    "starting" -> "Подключение"
                                    "error" -> "Ошибка"
                                    else -> "Отключено"
                                }, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                                    maxLines = 1, color = scheme.onSurface)
                                // Uptime appears ONLY in the power circle.
                                if (active) Text(
                                    String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                                        seconds / 3600, seconds / 60 % 60, seconds % 60),
                                    fontSize = 11.sp, color = scheme.onSurfaceVariant,
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
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(selected?.second?.let(::protocolLabel) ?: "Нет выбранного сервера",
                            color = scheme.onSurfaceVariant, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                            if (download >= 0) Text("↓ " + sizeLabel(download),
                                fontSize = 11.sp, color = scheme.onSurfaceVariant)
                            if (upload >= 0) Text("↑ " + sizeLabel(upload),
                                fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        }
                        if (status == "error" && onShowError != null)
                            TextButton(onClick = onShowError, modifier = Modifier.tvFocusRing()) {
                                Text("Причина ошибки")
                            }
                    }
                }
            }
        }
        item(key = "wide-server-heading") {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Серверы", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(subscriptions.sumOf { it.servers.size }.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
            }
        }
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
            val visible = owner.id !in collapsed
            val remote = owner.source.startsWith("https://", true) ||
                owner.source.startsWith("http://", true)
            item(key = "wide-group:" + owner.id) {
                Surface(
                    shape = RoundedCornerShape(14.dp), color = scheme.surface,
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = .22f))
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.weight(1f)
                                    .heightIn(min = 46.dp)
                                    .tvFocusRing()
                                    .clickable {
                                        collapsed = ArrayList(if (visible) collapsed + owner.id
                                            else collapsed - owner.id)
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
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp)
                                    if (remote) Text(
                                        "Обновление · " + intervalLabel(
                                            owner.customIntervalSeconds ?: owner.updateIntervalSeconds),
                                        color = scheme.onSurfaceVariant, fontSize = 10.sp)
                                }
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
                        if (remote) Row(
                            Modifier.fillMaxWidth().padding(start = 13.dp, end = 8.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Трафик " + traffic + " / " + total + "   ·   До " + expires,
                                fontSize = 10.sp, maxLines = 1,
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
                        if (owner.id in showDetails && remote) {
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
            if (visible) {
                profiles.chunked(gridColumns).forEachIndexed { index, pair ->
                    item(key = "wide-row:" + owner.id + ":" + index) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            pair.forEach { server ->
                                val id = owner.id + ":" + server.id
                                val active = selected?.first?.id == owner.id &&
                                    selected?.second?.id == server.id
                                val shape = RoundedCornerShape(13.dp)
                                Surface(
                                    shape = shape,
                                    border = BorderStroke(if (active) 2.dp else 1.dp,
                                        if (active) scheme.primary.copy(alpha = .7f)
                                        else scheme.outline.copy(alpha = .24f)),
                                    color = if (active) scheme.primaryContainer.copy(alpha = .40f)
                                        else scheme.surface,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = if (tv) 59.dp else 63.dp),
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
                                                    fontSize = 13.sp,
                                                    fontWeight = if (active) FontWeight.SemiBold
                                                        else FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis)
                                                Text(protocolLabel(server),
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = scheme.onSurfaceVariant)
                                                Text(SubscriptionPingText.display(
                                                    latency[id], measuring[id] == server.raw),
                                                    fontSize = 10.sp,
                                                    color = scheme.onSurfaceVariant)
                                            }
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
                        Text("Добавить подписку или ключ", maxLines = 1,
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
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    selectedForJson?.let { profile ->
        ServerConfigSheet(profile) { selectedForJson = null }
    }
}
