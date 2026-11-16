package xyz.wraithnet.phantom.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.data.AppRouting
import xyz.wraithnet.phantom.data.AppRoutingTransfer
import xyz.wraithnet.phantom.data.ConfigurationFiles
import xyz.wraithnet.phantom.service.PhantomVpnService
import java.io.InputStreamReader

private data class SelectableApp(val packageName: String, val title: String, val icon: ImageBitmap?)

@Composable
fun AppRoutingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AppRouting.mode(context)) }
    var selected by remember { mutableStateOf(AppRouting.packages(context)) }
    var search by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf(emptyList<SelectableApp>()) }
    val visible = remember(apps, search) {
        apps.filter { search.isBlank() || it.title.contains(search, true) ||
            it.packageName.contains(search, true) }
    }

    fun gentleTap() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    fun restore(input: String) {
        try {
            val incoming = AppRoutingTransfer.parse(input)
            selected = incoming.packages - context.packageName
            incoming.mode?.let { mode = it }
            gentleTap()
            message = "Список загружен. Нажмите «Применить» для сохранения."
        } catch (e: Exception) {
            message = e.message ?: "Не удалось прочитать список"
        }
    }

    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val input = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            InputStreamReader(stream, Charsets.UTF_8).use {
                                ConfigurationFiles.read(it, 100_000)
                            }
                        } ?: error("Не удалось открыть файл")
                    }
                    restore(input)
                } catch (e: Exception) {
                    message = e.message ?: "Ошибка импорта"
                }
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val snapshot = AppRoutingTransfer.export(mode, selected)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(snapshot.toByteArray(Charsets.UTF_8))
                        } ?: error("Не удалось сохранить файл")
                    }
                    gentleTap()
                    message = "Список сохранён"
                } catch (e: Exception) {
                    message = e.message ?: "Ошибка экспорта"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(query, 0)
            found.mapNotNull { item ->
                val pkg = item.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                SelectableApp(pkg, item.loadLabel(pm).toString().ifBlank { pkg },
                    runCatching { item.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull())
            }.distinctBy { it.packageName }.sortedBy { it.title.lowercase() }
        }
        loading = false
    }

    BackHandler { onBack() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Назад")
                }
                Text("Приложения и VPN", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "Импорт, экспорт и выбор приложений")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.widthIn(min = 264.dp, max = 320.dp),
                        shape = RoundedCornerShape(18.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 6.dp,
                        border = BorderStroke(1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                    ) {
                        DropdownMenuItem(text = { Text("Выбрать все видимые") }, onClick = {
                            selected = selected + visible.map { it.packageName }
                            gentleTap(); menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Инвертировать выбор") }, onClick = {
                            selected = selected.symmetricDifference(visible.map { it.packageName }.toSet())
                            gentleTap(); menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Очистить выбор") }, onClick = {
                            selected = emptySet()
                            gentleTap(); menuOpen = false
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Импорт выбранных · буфер") }, onClick = {
                            menuOpen = false
                            restore(clipboard.primaryClip?.getItemAt(0)
                                ?.coerceToText(context)?.toString().orEmpty())
                        })
                        DropdownMenuItem(text = { Text("Экспорт выбранных · буфер") }, onClick = {
                            menuOpen = false
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                "Phantom — приложения VPN", AppRoutingTransfer.export(mode, selected)))
                            gentleTap()
                            message = "Список скопирован"
                        })
                        DropdownMenuItem(text = { Text("Импорт выбранных · файл") }, onClick = {
                            menuOpen = false
                            importFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                        })
                        DropdownMenuItem(text = { Text("Экспорт выбранных · файл") }, onClick = {
                            menuOpen = false
                            exportFile.launch("phantom-apps.json")
                        })
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                message?.let {
                    Text(it, modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        if (mode == AppRouting.ONLY && selected.isEmpty()) {
                            message = "Выберите хотя бы одно приложение"
                            return@Button
                        }
                        AppRouting.save(context, mode, selected)
                        if (context.getSharedPreferences("phantom_preferences", 0)
                                .getBoolean("vpn_running", false)) {
                            runCatching {
                                context.startService(Intent(context, PhantomVpnService::class.java)
                                    .setAction(PhantomVpnService.ACTION_RELOAD))
                            }
                        }
                        gentleTap()
                        onBack()
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("Применить") }
            }
        }
    ) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Какие приложения должны работать через VPN?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                listOf(
                    AppRouting.ALL to "Все приложения",
                    AppRouting.ONLY to "Через VPN только выбранные",
                    AppRouting.EXCLUDE to "Выбранные — без VPN"
                ).forEach { (value, title) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        mode = value; gentleTap()
                    }.heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == value, onClick = {
                            mode = value; gentleTap()
                        })
                        Spacer(Modifier.width(6.dp))
                        Text(title)
                    }
                }
                if (mode != AppRouting.ALL) {
                    OutlinedTextField(value = search, onValueChange = { search = it },
                        label = { Text("Поиск приложений") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Text("Выбрано: " + selected.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (mode != AppRouting.ALL) {
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                items(visible, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (app.packageName in selected) selected - app.packageName
                            else selected + app.packageName
                        gentleTap()
                    }.heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = app.packageName in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + app.packageName
                                    else selected - app.packageName
                                gentleTap()
                            })
                        Spacer(Modifier.width(8.dp))
                        if (app.icon != null) {
                            Image(bitmap = app.icon, contentDescription = null,
                                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)))
                        } else {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center) {
                                Text(app.title.take(1).uppercase())
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.title, maxLines = 1)
                            Text(app.packageName, maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
                }
            }
        }
    }
}

private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> =
    (this - other) + (other - this)
