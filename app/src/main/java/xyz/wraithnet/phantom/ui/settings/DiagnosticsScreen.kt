package xyz.wraithnet.phantom.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.wraithnet.phantom.data.VpnEventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(VpnEventLog.read(context)) }
    val dateFormat = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                Text("Журнал VPN", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            Text("Последние события на этом устройстве. Конфигурации и ключи не сохраняются.",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { events = VpnEventLog.read(context); message = null }) {
                    Text("Обновить")
                }
                TextButton(
                    enabled = events.isNotEmpty(),
                    onClick = {
                        val body = events.joinToString("\n") {
                            dateFormat.format(Date(it.timeMillis)) + "  " +
                                it.state + (if (it.reason.isNotBlank()) ": " + it.reason else "")
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Phantom VPN log", body))
                        message = "Журнал скопирован"
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp)); Text("Копировать")
                }
                TextButton(onClick = {
                    VpnEventLog.clear(context)
                    events = emptyList()
                    message = "Журнал очищен"
                }, enabled = events.isNotEmpty()) { Text("Очистить") }
            }
            message?.let {
                Text(it, Modifier.padding(horizontal = 18.dp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (events.isEmpty()) {
                Text("Пока нет событий",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events) { event ->
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(event.state, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(dateFormat.format(Date(event.timeMillis)), fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (event.reason.isNotBlank()) {
                                Spacer(Modifier.height(5.dp))
                                Text(event.reason, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
