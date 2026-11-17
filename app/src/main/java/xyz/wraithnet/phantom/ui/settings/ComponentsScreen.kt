package xyz.wraithnet.phantom.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.wraithnet.phantom.BuildConfig

private data class LibraryLicense(val title: String, val license: String, val url: String)

private val componentLicenses = listOf(
    LibraryLicense(
        "sing-box / libbox (TUIC, AnyTLS)", "GNU GPL 3.0",
        "https://github.com/singbox-android/libbox/blob/main/LICENSE"
    ),
    LibraryLicense(
        "Xray-core", "Mozilla Public License 2.0",
        "https://github.com/XTLS/Xray-core/blob/main/LICENSE"
    ),
    LibraryLicense(
        "AndroidLibXrayLite", "GNU LGPL 3.0",
        "https://github.com/2dust/AndroidLibXrayLite/blob/main/LICENSE"
    ),
    LibraryLicense(
        "ZXing / QR", "Apache License 2.0",
        "https://github.com/zxing/zxing/blob/master/LICENSE"
    ),
    LibraryLicense(
        "ZXing Android Embedded", "Apache License 2.0",
        "https://github.com/journeyapps/zxing-android-embedded/blob/master/COPYING"
    ),
    LibraryLicense(
        "OkHttp / DNS-over-HTTPS", "Apache License 2.0",
        "https://github.com/square/okhttp/blob/parent-4.12.1/LICENSE.txt"
    ),
    LibraryLicense(
        "AndroidX / Jetpack Compose / WorkManager", "Apache License 2.0",
        "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"
    )
)

@Composable
fun ComponentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Назад")
                }
                Spacer(Modifier.width(8.dp))
                Text("Компоненты и лицензии",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("ВОЗМОЖНОСТИ КЛИЕНТА", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = .38f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("VPN-ядра Xray " + BuildConfig.XRAY_CORE_VERSION + " и sing-box 1.14.4",
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("Поддерживаемые форматы подключений",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("VLESS  ·  VMess  ·  Trojan  ·  Shadowsocks  ·  Hysteria2  ·  TUIC v5  ·  AnyTLS  ·  Xray JSON",
                            fontSize = 14.sp, lineHeight = 22.sp)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .22f))
                        Text("Транспорты и защита", fontWeight = FontWeight.Medium)
                        Text("TCP, WebSocket, gRPC, HTTPUpgrade, mKCP, " +
                            "XHTTP (HTTP/2 и HTTP/3 через QUIC), TLS и REALITY.",
                            fontSize = 13.sp, lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .22f))
                        Text("QUIC и современные UDP-транспорты",
                            fontWeight = FontWeight.Medium)
                        Text("Hysteria2 использует QUIC. XHTTP работает через HTTP/3 при TLS и ALPN h3. " +
                            "MASQUE доступен через совместимый Xray JSON; работу конкретного сервера " +
                            "нужно проверять подключением. Старый транспорт network=quic удалён из Xray.",
                            fontSize = 13.sp, lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("ОТКРЫТЫЕ КОМПОНЕНТЫ", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = .38f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        componentLicenses.forEachIndexed { index, item ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW,
                                            Uri.parse(item.url)))
                                    }
                                }.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium)
                                    Text(item.license, fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, "Открыть лицензию",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (index != componentLicenses.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Ссылки открывают полные тексты лицензий на сайтах разработчиков. " +
                    "Phantom использует эти компоненты в составе Android-приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp)
            }
        }
    }
}
