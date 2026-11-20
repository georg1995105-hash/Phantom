package xyz.wraithnet.phantom.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.wraithnet.phantom.data.LatencyProbe

@Composable
fun PingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var timeout by remember { mutableFloatStateOf(LatencyProbe.timeoutMs(context) / 1000f) }
    BackHandler { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                Text("Пинг серверов", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    ) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Text("Проверка пинга", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Пинг TCP показывает время соединения с сервером или входом CDN. Для QUIC и составных маршрутов без однозначного TCP-входа показано время ответа через профиль.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Text("Тайм-аут: " + timeout.toInt() + " сек",
                fontWeight = FontWeight.SemiBold)
            Slider(value = timeout, onValueChange = { timeout = it },
                valueRange = 2f..12f, steps = 9)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    LatencyProbe.configure(context, timeout.toInt())
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }
}
