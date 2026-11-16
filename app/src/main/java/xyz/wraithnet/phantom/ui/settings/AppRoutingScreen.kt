package xyz.wraithnet.phantom.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.data.AppRouting
import xyz.wraithnet.phantom.service.PhantomVpnService

private data class SelectableApp(
    val packageName: String,
    val title: String,
    val icon: ImageBitmap?,
)

@Composable
fun AppRoutingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppRouting.mode(context)) }
    var selected by remember { mutableStateOf(AppRouting.packages(context)) }
    var search by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf(emptyList<SelectableApp>()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val activities = pm.queryIntentActivities(intent, 0)
            activities.mapNotNull { result ->
                val pkg = result.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                SelectableApp(
                    pkg,
                    result.loadLabel(pm).toString().ifBlank { pkg },
                    runCatching { result.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                )
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
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                Text("Приложения и VPN", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
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
                            context.startService(Intent(context, PhantomVpnService::class.java)
                                .setAction(PhantomVpnService.ACTION_RELOAD))
                        }
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Применить") }
            }
        }
    ) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Какие приложения должны работать через VPN?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val options = listOf(
                    AppRouting.ALL to "Все приложения",
                    AppRouting.ONLY to "Через VPN только выбранные",
                    AppRouting.EXCLUDE to "Выбранные — без VPN"
                )
                options.forEach { (value, title) ->
                    Row(Modifier.fillMaxWidth().clickable { mode = value }.heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == value, onClick = { mode = value })
                        Spacer(Modifier.width(6.dp))
                        Text(title)
                    }
                }
                if (mode != AppRouting.ALL) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        label = { Text("Поиск приложений") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Выбрано: " + selected.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (mode != AppRouting.ALL) {
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                items(apps.filter {
                    search.isBlank() ||
                        it.title.contains(search, ignoreCase = true) ||
                        it.packageName.contains(search, ignoreCase = true)
                }, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (app.packageName in selected) selected - app.packageName
                            else selected + app.packageName
                    }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = app.packageName in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + app.packageName
                                    else selected - app.packageName
                            })
                        Spacer(Modifier.width(8.dp))
                        if (app.icon != null) {
                            Image(bitmap = app.icon, contentDescription = null,
                                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)))
                        } else {
                            Box(Modifier.size(38.dp)
                                .clip(RoundedCornerShape(9.dp))
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
