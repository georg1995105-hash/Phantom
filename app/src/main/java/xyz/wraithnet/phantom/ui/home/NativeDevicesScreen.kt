package xyz.wraithnet.phantom.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionDevicesApi

/** Full native device screen. No webview, browser, bot or payment-account dependency. */
@Composable
internal fun NativeDevicesScreen(sub: Subscription, localHwid: String, back: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var data by remember(sub.source) { mutableStateOf<SubscriptionDevicesApi.Snapshot?>(null) }
    var failure by remember(sub.source) { mutableStateOf<String?>(null) }
    var loading by remember(sub.source) { mutableStateOf(false) }
    var busy by remember(sub.source) { mutableStateOf<String?>(null) }
    var confirm by remember(sub.source) { mutableStateOf<SubscriptionDevicesApi.Device?>(null) }
    var generation by remember(sub.source) { mutableIntStateOf(0) }
    var feedback by remember(sub.source) { mutableStateOf<String?>(null) }

    BackHandler { if (busy == null && confirm == null) back() }
    LaunchedEffect(sub.source, generation) {
        loading = true
        failure = null
        try {
            data = SubscriptionDevicesApi.list(sub.source, localHwid)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            failure = e.message ?: "Не удалось получить устройства."
        } finally { loading = false }
    }

    val target = confirm
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (busy == null) confirm = null },
            title = { Text("Отключить устройство?") },
            text = { Text((target.model ?: "Устройство") +
                (if (target.current) " · Это устройство" else "") +
                "\n\nМесто в подписке освободится. Устройство сможет подключиться снова, если на нём осталась ссылка подписки.") },
            confirmButton = {
                TextButton(enabled = busy == null, modifier = Modifier.tvFocusRing(),
                    onClick = {
                        val source = sub.source
                        val chosen = target.id
                        busy = chosen
                        scope.launch {
                            try {
                                val updated = SubscriptionDevicesApi.remove(source, localHwid, chosen)
                                if (updated.devices.any { it.id == chosen })
                                    throw IllegalStateException("Сервер не подтвердил отключение устройства.")
                                data = updated
                                feedback = "Устройство отключено. Место освобождено."
                                failure = null
                                confirm = null
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (e: Exception) {
                                failure = e.message ?: "Не удалось отключить устройство."
                                confirm = null
                            } finally { busy = null }
                        }
                    }) { Text("Отключить") }
            },
            dismissButton = {
                TextButton(enabled = busy == null, modifier = Modifier.tvFocusRing(),
                    onClick = { confirm = null }) { Text("Отмена") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, enabled = busy == null, modifier = Modifier.tvFocusRing()) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.width(6.dp))
            Text("Мои устройства", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { generation++ }, enabled = !loading && busy == null,
                modifier = Modifier.tvFocusRing()) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить список устройств")
            }
        }
        data?.let { state ->
            Spacer(Modifier.height(8.dp))
            Text(state.title, style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            val capacity = state.limit?.let { state.count.toString() + " из " + it }
                ?: (state.count.toString() + " · без ограничения")
            Text("Подключённые устройства: " + capacity,
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (state.limit != null && state.count >= state.limit) {
                Spacer(Modifier.height(6.dp))
                Text("Лимит достигнут. Отключите устройство, которым больше не пользуетесь.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (loading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        failure?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { generation++ }, enabled = !loading && busy == null,
                modifier = Modifier.tvFocusRing()) { Text("Повторить") }
        }
        feedback?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(message, color = colors.primary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        val state = data
        if (!loading && failure == null && state?.devices?.isEmpty() == true) {
            Text("В этой подписке пока нет зарегистрированных устройств.",
                color = colors.onSurfaceVariant)
        }
        if (state != null) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(state.devices, key = { it.id }) { device ->
                    val shape = RoundedCornerShape(16.dp)
                    Surface(
                        color = colors.surfaceVariant.copy(alpha = .37f),
                        shape = shape,
                        border = BorderStroke(1.dp, colors.outline.copy(alpha = .25f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Devices, null, tint = colors.primary,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(9.dp))
                                Text(device.model ?: device.platform ?: "Устройство",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f))
                            }
                            if (device.current) {
                                Spacer(Modifier.height(3.dp))
                                Text("Это устройство", color = colors.primary,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            val detail = listOfNotNull(
                                device.platform,
                                device.osVersion?.let { "ОС " + it },
                                device.app,
                            ).distinct().joinToString(" · ")
                            if (detail.isNotEmpty()) {
                                Spacer(Modifier.height(5.dp))
                                Text(detail, color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            device.addedAt?.let {
                                Spacer(Modifier.height(5.dp))
                                Text("Добавлено " + deviceDate(it), color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (state.canDelete) {
                                Spacer(Modifier.height(11.dp))
                                OutlinedButton(
                                    onClick = { confirm = device; feedback = null; failure = null },
                                    enabled = busy == null,
                                    modifier = Modifier.fillMaxWidth().tvFocusRing()
                                ) { Text("Отключить устройство") }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun deviceDate(raw: String): String {
    val date = raw.take(10)
    return if (date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        date.substring(8, 10) + "." + date.substring(5, 7) + "." + date.substring(0, 4)
    else raw.take(22)
}
