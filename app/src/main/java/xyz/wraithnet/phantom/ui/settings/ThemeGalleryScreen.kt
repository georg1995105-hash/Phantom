package xyz.wraithnet.phantom.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode
import xyz.wraithnet.phantom.ui.theme.themeScheme

/** Separate theme gallery with previews showing the real theme palette. */
@Composable
fun ThemeGalleryScreen(
    selected: PhantomThemeMode,
    onChoose: (PhantomThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    BackHandler { onBack() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Назад")
                }
                Text("Оформление", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Выберите внешний вид Phantom",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text("Коснитесь цветной карточки — оформление изменится сразу.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            item {
                ThemeOptionCard(PhantomThemeMode.AUTO, selected, systemDark, onChoose,
                    "Собственная спокойная палитра для светлого и тёмного Android")
            }
            item {
                ThemeOptionCard(PhantomThemeMode.LIGHT, selected, systemDark, onChoose,
                    "Мягкий серо-светлый фон без резкой белизны")
            }
            item {
                ThemeOptionCard(PhantomThemeMode.GRAPHITE, selected, systemDark, onChoose,
                    "Графитовые поверхности, мягкий голубой акцент")
            }
            item {
                ThemeOptionCard(PhantomThemeMode.NIGHT, selected, systemDark, onChoose,
                    "Глубокие тёмные оттенки с фиолетовым акцентом")
            }
            item {
                ThemeOptionCard(PhantomThemeMode.FOREST, selected, systemDark, onChoose,
                    "Тёмный хвойный фон и спокойные зелёные акценты")
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    option: PhantomThemeMode,
    selected: PhantomThemeMode,
    systemDark: Boolean,
    onChoose: (PhantomThemeMode) -> Unit,
    description: String,
) {
    val haptic = LocalHapticFeedback.current
    val colors = themeScheme(option, systemDark)
    val active = option == selected
    val borderColor = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline.copy(alpha = .25f)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(if (active) 2.dp else 1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChoose(option)
            }
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(option.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, maxLines = 2)
            }
            if (active) {
                Box(Modifier.size(27.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Box(Modifier.size(27.dp).border(1.5.dp,
                    MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(colors.background, colors.surface, colors.primary, colors.secondary)
                .forEach { tint ->
                    Box(Modifier.size(20.dp).clip(CircleShape)
                        .background(tint).border(1.dp, Color.Gray.copy(alpha=.3f), CircleShape))
                }
        }
        Spacer(Modifier.height(10.dp))
        // Miniature of the actual app colors, not a decorative color name alone.
        Row(
            Modifier.fillMaxWidth().height(90.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.background).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(58.dp).clip(CircleShape)
                    .background(colors.primaryContainer)
                    .border(2.dp, colors.primary.copy(alpha=.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(29.dp),
                    tint = colors.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(.6f).height(9.dp)
                    .clip(RoundedCornerShape(5.dp)).background(colors.onBackground))
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth(.4f).height(6.dp)
                    .clip(RoundedCornerShape(4.dp)).background(colors.onSurfaceVariant))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(27.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(colors.surface).padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(colors.primary))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.fillMaxWidth(.65f).height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.onSurfaceVariant.copy(alpha=.8f)))
                }
            }
        }
    }
}
