package xyz.wraithnet.phantom.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import xyz.wraithnet.phantom.ui.home.tvFocusRing
import xyz.wraithnet.phantom.ui.home.isTelevision
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
    val tv = isTelevision()
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
            Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Text("Проверка пинга", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Для XHTTP, VLESS, Hysteria2 и остальных прежних протоколов используется TCP-пинг входа. TUIC проверяется через отдельное QUIC-соединение и доступен по кнопке «Пинг».",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = if (tv) 64.dp else 48.dp)
                .tvFocusRing()
                .toggleable(value = autoTuic, role = Role.Switch,
                    onValueChange = { autoTuic = it })
                .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автоматический пинг TUIC", fontWeight = FontWeight.SemiBold)
                    Text("Дополнительный процесс sing-box с QUIC. По умолчанию отключён для стабильности соединения; вручную доступен в списке серверов.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoTuic, onCheckedChange = null)
            }
            HorizontalDivider()
            Text("Тайм-аут: " + timeout.toInt() + " сек",
                fontWeight = FontWeight.SemiBold)
            if (tv) {
                // A TV remote has no drag gesture: one D-pad target per action.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { timeout = (timeout - 1f).coerceAtLeast(2f) },
                        enabled = timeout > 2f,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp).tvFocusRing()
                    ) { Text("− 1 сек") }
                    OutlinedButton(
                        onClick = { timeout = (timeout + 1f).coerceAtMost(12f) },
                        enabled = timeout < 12f,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp).tvFocusRing()
                    ) { Text("+ 1 сек") }
                }
            } else {
                Slider(value = timeout, onValueChange = { timeout = it },
                    valueRange = 2f..12f, steps = 9)
            }
            Button(
                onClick = {
                    LatencyProbe.configure(context, timeout.toInt())
                    LatencyProbe.setAutoTuicEnabled(context, autoTuic)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).tvFocusRing()
            ) { Text("Сохранить") }
        }
    }
}
