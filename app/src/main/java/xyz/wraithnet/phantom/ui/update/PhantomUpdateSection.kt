package xyz.wraithnet.phantom.ui.update

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.wraithnet.phantom.data.PhantomRelease
import xyz.wraithnet.phantom.data.PhantomUpdates
import xyz.wraithnet.phantom.data.ReleaseNotes
import java.io.File

/** Updates are checked automatically and installed only with explicit consent. */
@Composable
fun PhantomUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<PhantomRelease?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var downloadSpeed by remember { mutableLongStateOf(0L) }
    var status by remember { mutableStateOf<String?>(null) }

    fun check() {
        if (checking || downloading) return
        checking = true
        status = null
        scope.launch {
            try {
                release = PhantomUpdates.check(context)
                status = if (release == null) "Установлена актуальная версия"
                    else (if (release?.isBeta == true) "Доступна бета-версия "
                        else "Доступна версия ") + release?.version
            } catch (e: Exception) {
                status = e.message ?: "Не удалось проверить обновления"
            } finally {
                checking = false
            }
        }
    }

    suspend fun install(file: File) {
        try {
            val selected = release ?: return
            val launched = PhantomUpdates.openInstaller(context, file, selected)
            status = if (launched) "Android устанавливает обновление или запросит подтверждение"
                else "Разрешите установку приложений и нажмите «Установить» снова"
        } catch (e: Exception) {
            status = e.message ?: "Не удалось открыть установщик"
        }
    }

    LaunchedEffect(Unit) { check() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Обновления приложения", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text("Проверка, скачивание и установка APK",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp,
                color = if (it.startsWith("Не удалось") || it.startsWith("Ошибка"))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        release?.notes?.let { notes ->
            val summary = ReleaseNotes.summary(notes)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Что изменилось", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
                Text(summary, fontSize = 11.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (downloading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.toFloat() / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Загрузка " + progress + "%" + if (downloadSpeed > 0L)
                " · ${downloadSpeed / 1024L} КБ/с" else "", fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = ::check, enabled = !checking && !downloading) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Проверить")
            }
            if (release != null) {
                Button(
                    enabled = !checking && !downloading,
                    onClick = {
                        val file = downloadedFile
                        if (file != null && file.exists()) {
                            downloading = true
                            scope.launch {
                                try { install(file) }
                                finally { downloading = false }
                            }
                        } else {
                            val selected = release ?: return@Button
                            downloading = true
                            progress = 0
                            status = null
                            scope.launch {
                                try {
                                    val apk = PhantomUpdates.download(context, selected,
                                        onProgress = { p -> progress = p },
                                        onSpeed = { downloadSpeed = it })
                                    downloadedFile = apk
                                    install(apk)
                                } catch (e: Exception) {
                                    status = e.message ?: "Ошибка загрузки APK"
                                } finally {
                                    downloading = false
                                }
                            }
                        }
                    }
                ) {
                    Text(if (downloadedFile?.exists() == true) "Установить"
                        else "Скачать и установить")
                }
            }
        }
    }
}
