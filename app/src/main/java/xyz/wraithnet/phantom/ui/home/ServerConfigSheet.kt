package xyz.wraithnet.phantom.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.data.ClientConfigInspector
import xyz.wraithnet.phantom.data.CountryFlags
import xyz.wraithnet.phantom.data.ServerProfile

/**
 * A persistent bottom sheet opened by holding a server row. No navigation, server
 * selection or VPN action occurs. Copy/export uses the same text currently visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerConfigSheet(server: ServerProfile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember(server.raw) { mutableStateOf<ClientConfigInspector.Document?>(null) }
    var failed by remember(server.raw) { mutableStateOf(false) }
    var revealed by remember(server.raw) { mutableStateOf(false) }
    var requestReveal by remember(server.raw) { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(server.raw) {
        val result = withContext(Dispatchers.Default) {
            runCatching { ClientConfigInspector.inspect(server) }.getOrNull()
        }
        document = result
        failed = result == null
    }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(payload)
                        } ?: error("Не удалось открыть файл")
                    }.isSuccess
                }
                message = if (success) "JSON сохранён" else "Не удалось сохранить JSON"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(CountryFlags.flag(server) + " " + displayServerName(server),
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(document?.engine ?: "Конфигурация ноды",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            if (failed) {
                Text("Не удалось подготовить JSON этой ноды.",
                    color = MaterialTheme.colorScheme.error)
            } else if (document == null) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                val visible = if (revealed) document!!.full else document!!.masked
                val preview = visible.take(ClientConfigInspector.PREVIEW_CHARS)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
                ) {
                    SelectionContainer {
                        Text(
                            preview,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                if (visible.length > preview.length) {
                    Text("Показано начало JSON. Копирование и сохранение содержат весь документ.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    if (revealed) revealed = false else requestReveal = true
                }) {
                    Icon(if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (revealed) "Скрыть секреты" else "Показать секреты")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Phantom JSON", visible))
                            message = "JSON скопирован"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Копировать", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            pendingExport = visible
                            exporter.launch("phantom-node.json")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Сохранить", fontSize = 12.sp)
                    }
                }
                Text("Секреты скрыты по умолчанию. Полный JSON содержит ключ доступа.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
    }
    if (requestReveal) {
        AlertDialog(
            onDismissRequest = { requestReveal = false },
            title = { Text("Показать секреты?") },
            text = { Text("UUID, пароли и другие ключи станут видны. При копировании или сохранении полного JSON они также будут включены.") },
            confirmButton = {
                TextButton(onClick = { revealed = true; requestReveal = false }) {
                    Text("Показать")
                }
            },
            dismissButton = {
                TextButton(onClick = { requestReveal = false }) { Text("Отмена") }
            }
        )
    }
}
