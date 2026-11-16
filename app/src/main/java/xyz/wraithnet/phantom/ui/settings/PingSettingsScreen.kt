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
    var mode by remember { mutableStateOf(LatencyProbe.mode(context)) }
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
            Text("Способ проверки", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            listOf(
                LatencyProbe.TCP to "TCP · быстро",
                LatencyProbe.PROXY to "Через VPN-прокси · проверка соединения"
            ).forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable { mode = value }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == value, onClick = { mode = value })
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
            Text(
                if (mode == LatencyProbe.TCP)
                    "TCP проверяет доступность порта, но не подтверждает работу VPN. Hysteria2 использует UDP."
                else "Проверка через Xray может быть медленнее, но оценивает реальное подключение.",
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
                    LatencyProbe.configure(context, mode, timeout.toInt())
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }
}
