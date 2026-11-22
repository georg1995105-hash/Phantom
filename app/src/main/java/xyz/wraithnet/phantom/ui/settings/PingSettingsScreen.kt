package xyz.wraithnet.phantom.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.tvFocusRing().clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import xyz.wraithnet.phantom.ui.home.tvFocusRing
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
    var autoTuic by remember { mutableStateOf(LatencyProbe.autoTuicEnabled(context)) }
    BackHandler { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.tvFocusRing()) { Icon(Icons.Default.ArrowBack, "Назад") }
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
                "Для XHTTP, VLESS, Hysteria2 и остальных прежних протоколов используется TCP-пинг входа. TUIC проверяется через отдельное QUIC-соединение и доступен по кнопке «Пинг».",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автоматический пинг TUIC", fontWeight = FontWeight.SemiBold)
                    Text("Дополнительный процесс sing-box с QUIC. По умолчанию отключён для стабильности соединения; вручную доступен в списке серверов.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoTuic, onCheckedChange = { autoTuic = it })
            }
            HorizontalDivider()
            Text("Тайм-аут: " + timeout.toInt() + " сек",
                fontWeight = FontWeight.SemiBold)
            Slider(value = timeout, onValueChange = { timeout = it },
                valueRange = 2f..12f, steps = 9)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    LatencyProbe.configure(context, timeout.toInt())
                    LatencyProbe.setAutoTuicEnabled(context, autoTuic)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }
}
