package xyz.wraithnet.phantom.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.tvFocusRing().clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import xyz.wraithnet.phantom.ui.home.tvFocusRing
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.data.ConfigurationFiles
import xyz.wraithnet.phantom.data.PhantomBackup
import java.io.InputStreamReader

/** User-controlled, local archive; never uploads credentials or runs an imported profile. */
@Composable
fun BackupScreen(onBack: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PhantomBackup.Snapshot?>(null) }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        // Capture only after the user selects a destination.
                        val data = PhantomBackup.capture(context)
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(data.toByteArray(Charsets.UTF_8))
                        } ?: error("Не удалось записать файл")
                    }
                    feedback = "Резервная копия сохранена"
                } catch (e: Exception) {
                    feedback = e.message ?: "Не удалось сохранить резервную копию"
                } finally {
                    busy = false
                }
            }
        }
    }

    val import = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                                PhantomBackup.parse(
                                    ConfigurationFiles.read(reader, PhantomBackup.MAX_SIZE))
                            }
                        } ?: error("Не удалось открыть файл")
                    }
                    pending = snapshot
                } catch (e: Exception) {
                    feedback = e.message ?: "Не удалось прочитать резервную копию"
                } finally {
                    busy = false
                }
            }
        }
    }

    BackHandler { onBack() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.tvFocusRing()) {
                    Icon(Icons.Default.ArrowBack, "Назад")
                }
                Spacer(Modifier.width(8.dp))
                Text("Резервная копия", style = MaterialTheme.typography.titleLarge,
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
                Text("Перенос Phantom на другое устройство или восстановление после переустановки.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = .45f)),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.fillMaxWidth().tvFocusRing().clickable(enabled = !busy) {
                        export.launch("phantom-backup.json")
                    }.padding(horizontal = 17.dp, vertical = 19.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SaveAlt, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(15.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Сохранить резервную копию", fontWeight = FontWeight.SemiBold)
                            Text("Подписки, настройки и выбранные приложения",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .24f))
                    Row(Modifier.fillMaxWidth().tvFocusRing().clickable(enabled = !busy) {
                        import.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }.padding(horizontal = 17.dp, vertical = 19.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(15.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Восстановить из файла", fontWeight = FontWeight.SemiBold)
                            Text("Проверить архив перед добавлением подписок",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
            item {
                Text("Копия содержит приватные ссылки и ключи подключения, а также ID клиента. " +
                    "Файл не шифруется — сохраняйте его только в надёжном месте. " +
                    "Восстановление объединяет подписки с существующими; " +
                    "одноимённые записи заменяются и возвращаются сохранённые настройки. " +
                    "Текущий VPN должен быть отключён.",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                feedback?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }

    pending?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Восстановить резервную копию?") },
            text = {
                Text("В архиве подписок: ${snapshot.count}. " +
                    "Новые подписки будут добавлены, совпадающие обновлены, " +
                    "а сохранённые настройки заменят текущие. " +
                    "Функции подключения сами не включатся.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    pending = null
                    scope.launch {
                        busy = true
                        try {
                            val count = withContext(Dispatchers.IO) {
                                PhantomBackup.restore(context, snapshot)
                            }
                            feedback = "Восстановлено подписок: $count"
                            onRestored()
                        } catch (e: Exception) {
                            feedback = e.message ?: "Восстановление не удалось"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Восстановить") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Отмена") }
            }
        )
    }
}
