package xyz.wraithnet.phantom.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import xyz.wraithnet.phantom.data.PhantomHttp
import xyz.wraithnet.phantom.data.VpnDnsSettings

@Composable
fun DnsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("phantom_preferences", 0) }
    var settings by remember { mutableStateOf(VpnDnsSettings.load(context)) }
    var fallback by remember {
        mutableStateOf(prefs.getBoolean(PhantomHttp.DNS_FALLBACK_KEY, true))
    }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                Spacer(Modifier.width(8.dp))
                Text("DNS", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = {
                        val validation = settings.validate()
                        if (validation != null) {
                            message = validation
                        } else if (VpnDnsSettings.save(context, settings) &&
                            prefs.edit().putBoolean(PhantomHttp.DNS_FALLBACK_KEY, fallback).commit()
                        ) {
                            onBack()
                        } else {
                            message = "Не удалось сохранить DNS"
                        }
                    },
                    enabled = settings.validate() == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Сохранить") }
            }
        }
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("DNS для сайтов внутри VPN",
                style = MaterialTheme.typography.titleMedium)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = .25f)),
            ) {
                val choices = listOf(
                    Triple(VpnDnsSettings.Mode.DEFAULT, "Стандартный",
                        "Рекомендуется · 1.1.1.1 и 8.8.8.8"),
                    Triple(VpnDnsSettings.Mode.CLOUDFLARE, "Cloudflare", "1.1.1.1 и 1.0.0.1"),
                    Triple(VpnDnsSettings.Mode.GOOGLE, "Google", "8.8.8.8 и 8.8.4.4"),
                    Triple(VpnDnsSettings.Mode.CUSTOM, "Свой DNS", "Введите адреса вручную"),
                )
                choices.forEachIndexed { index, (choice, title, description) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            settings = settings.copy(mode = choice)
                            message = null
                        }.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.mode == choice,
                            onClick = { settings = settings.copy(mode = choice); message = null }
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(description, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (index != choices.lastIndex) HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
                }
                if (settings.mode == VpnDnsSettings.Mode.CUSTOM) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        OutlinedTextField(
                            value = settings.customPrimary,
                            onValueChange = {
                                settings = settings.copy(customPrimary = it.take(50))
                                message = null
                            },
                            label = { Text("Основной DNS · IP") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            isError = settings.customPrimary.isNotBlank() &&
                                !VpnDnsSettings.isIpLiteral(settings.customPrimary.trim())
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = settings.customSecondary,
                            onValueChange = {
                                settings = settings.copy(customSecondary = it.take(50))
                                message = null
                            },
                            label = { Text("Запасной DNS · необязательно") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Text("Сетевые настройки", style = MaterialTheme.typography.titleMedium)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.fillMaxWidth().clickable { fallback = !fallback }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Защищённый запасной DNS", fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text("Для загрузки подписок и обновлений, когда DNS телефона не отвечает. " +
                            "Не обходит DNS другого VPN.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = fallback, onCheckedChange = { fallback = it })
                }
            }
            Text("Изменение DNS внутри VPN применяется при следующем подключении. " +
                "Сложные DNS-правила, заданные в собственном JSON, при выборе другого " +
                "поставщика будут заменены.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
