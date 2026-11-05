// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.smart.SmartLearning
import helium314.keyboard.smart.SmartPrefs
import helium314.keyboard.smart.ui.FunctionsPanelView
import helium314.keyboard.smart.ui.SmartUi

/** Keys of SmartVoice settings that have no own preference value. */
object SvSettingKeys {
    const val LANGUAGES = "sv_languages_entry"
    const val RESIZE_INFO = "sv_resize_info"
    const val RESIZE_RESET = "sv_resize_reset"
    const val ONE_HAND_INFO = "sv_one_hand_info"
    const val SYMBOL_LAYOUTS = "sv_symbol_layouts"
    const val MORE_KEYS = "sv_more_keys"
    const val ALL_CORRECTIONS = "sv_all_corrections"
    const val GESTURE_MORE = "sv_gesture_more"
    const val VOICE_PERMISSION = "sv_voice_permission"
    const val VOICE_ENGINE = "sv_voice_engine"
    const val CLIP_SENSITIVE = "sv_clip_sensitive"
    const val CLIP_CLEAR = "sv_clip_clear"
    const val THEME_GALLERY = "sv_theme_gallery"
    const val THEME_MORE = "sv_theme_more"
    const val LEARNING_CLEAR = "sv_learning_clear"
    const val DICTIONARIES = "sv_dictionaries"
    const val STORAGE_INFO = "sv_storage_info"
    const val ADVANCED = "sv_advanced"
}

@Composable
fun SvMainSettingsScreen(
    onNavigate: (String) -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))) {
                @Composable
                fun Section(title: Int, summary: String, icon: Int, target: String) {
                    Preference(
                        name = stringResource(title),
                        description = summary,
                        onClick = { onNavigate(target) },
                        icon = icon
                    ) { NextScreenIcon() }
                }
                Section(R.string.sv_set_keyboard_main, stringResource(R.string.sv_set_keyboard_main_summary), R.drawable.ic_settings_layout, SettingsDestination.SvLayout)
                Section(R.string.sv_set_languages_input, stringResource(R.string.sv_set_languages_input_summary), R.drawable.ic_settings_languages, SettingsDestination.SvInput)
                Section(R.string.sv_set_voice_correct, stringResource(R.string.sv_set_voice_correct_summary), R.drawable.ic_sv_mic, SettingsDestination.SvVoice)
                Section(R.string.sv_set_clipboard, stringResource(R.string.sv_set_clipboard_summary), R.drawable.ic_sv_clipboard, SettingsDestination.SvClipboard)
            }
        }
    }
}

@Composable
fun SvLayoutScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sv_set_layout),
        settings = listOf(
            Settings.PREF_SHOW_NUMBER_ROW,
            Settings.PREF_SHOW_HINTS,
            Settings.PREF_SHOW_EMOJI_KEY,
            Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY,
            SvSettingKeys.THEME_GALLERY,
            Settings.PREF_THEME_KEY_BORDERS,
            Settings.PREF_VIBRATE_ON,
            SvSettingKeys.RESIZE_RESET,
        )
    )
}

@Composable
fun SvInputScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    recomposeOnPrefChange()
    val gesture = prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sv_set_input),
        settings = listOf(
            SvSettingKeys.LANGUAGES,
            Settings.PREF_GESTURE_INPUT,
            Settings.PREF_SHOW_SUGGESTIONS,
            Settings.PREF_AUTO_CORRECTION,
            Settings.PREF_AUTO_CAP,
            Settings.PREF_KEY_USE_PERSONALIZED_DICTS,
        )
    )
}

@Composable
fun SvVoiceScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sv_set_voice),
        settings = listOf(
            SvSettingKeys.VOICE_PERMISSION,
            SvSettingKeys.VOICE_ENGINE,
            SmartPrefs.VOICE_LANGUAGE,
            SmartPrefs.VOICE_SMART_PUNCTUATION,
            SmartPrefs.VOICE_REMOVE_FILLERS,
            SmartPrefs.VOICE_PREFER_OFFLINE,
            SmartPrefs.VOICE_CONTINUOUS,
            SmartPrefs.VOICE_COMMANDS,
            SmartPrefs.VOICE_VOCABULARY_ENABLED,
            SmartPrefs.VOICE_VOCABULARY,
        )
    )
}

@Composable
fun SvClipboardScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    recomposeOnPrefChange()
    val history = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    val chip = prefs.getBoolean(SmartPrefs.CLIP_CHIP, SmartPrefs.DEF_CLIP_CHIP)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sv_set_clipboard),
        settings = listOf(
            Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
            if (history) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
            if (history) Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST else null,
            if (history) Settings.PREF_CLIPBOARD_USE_FILES else null,
            "sv_screenshot_setup",
            SmartPrefs.CLIP_CHIP,
            if (chip) SmartPrefs.CLIP_CHIP_MINUTES else null,
            SvSettingKeys.CLIP_SENSITIVE,
            if (history) SvSettingKeys.CLIP_CLEAR else null,
        )
    )
}

@Composable
fun SvThemeScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sv_set_theme),
        settings = listOf(
            SvSettingKeys.THEME_GALLERY,
            Settings.PREF_THEME_KEY_BORDERS,
            SvSettingKeys.THEME_MORE,
        )
    )
}

@Composable
fun SvPrivacyScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sv_set_privacy),
        settings = listOf(
            Settings.PREF_KEY_USE_PERSONALIZED_DICTS,
            SvSettingKeys.LEARNING_CLEAR,
            Settings.PREF_ALWAYS_INCOGNITO_MODE,
            SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY,
            SvSettingKeys.DICTIONARIES,
            SvSettingKeys.STORAGE_INFO,
            SvSettingKeys.ADVANCED,
        )
    )
}

@Composable
private fun recomposeOnPrefChange() {
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "trigger recomposition on preference change")
}

private fun toast(context: Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

fun createSvSettings(context: Context) = listOf(
    Setting(context, SvSettingKeys.LANGUAGES, R.string.sv_set_languages_input) {
        Preference(name = it.title, onClick = { SettingsDestination.navigateTo(SettingsDestination.Languages) }) { NextScreenIcon() }
    },
    Setting(context, SvSettingKeys.RESIZE_INFO, R.string.sv_set_resize, R.string.sv_set_resize_summary) {
        Preference(name = it.title, description = it.description, onClick = { })
    },
    Setting(context, SvSettingKeys.RESIZE_RESET, R.string.sv_set_resize_reset, R.string.sv_set_resize_reset_summary) {
        val ctx = LocalContext.current
        Preference(name = it.title, description = it.description, onClick = {
            val prefs = ctx.prefs()
            val prefixes = listOf(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
                Settings.PREF_SIDE_PADDING_SCALE_PREFIX)
            prefs.edit { prefs.all.keys.filter { k -> prefixes.any { p -> k.startsWith(p) } }.forEach { k -> remove(k) } }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
            toast(ctx, R.string.sv_set_resize_reset_done)
        })
    },
    Setting(context, SvSettingKeys.ONE_HAND_INFO, R.string.sv_set_one_hand, R.string.sv_set_one_hand_summary) {
        Preference(name = it.title, description = it.description, onClick = { })
    },
    Setting(context, SvSettingKeys.SYMBOL_LAYOUTS, R.string.sv_set_symbol_layouts) {
        Preference(name = it.title, onClick = { SettingsDestination.navigateTo(SettingsDestination.Layouts) }) { NextScreenIcon() }
    },
    Setting(context, SvSettingKeys.MORE_KEYS, R.string.sv_set_more_keys, R.string.sv_set_more_keys_summary) {
        Preference(name = it.title, description = it.description,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.Preferences) }) { NextScreenIcon() }
    },
    Setting(context, SvSettingKeys.ALL_CORRECTIONS, R.string.sv_set_all_corrections) {
        Preference(name = it.title, onClick = { SettingsDestination.navigateTo(SettingsDestination.TextCorrection) }) { NextScreenIcon() }
    },
    Setting(context, SvSettingKeys.GESTURE_MORE, R.string.sv_set_gesture_more) {
        Preference(name = it.title, onClick = { SettingsDestination.navigateTo(SettingsDestination.GestureTyping) }) { NextScreenIcon() }
    },
    Setting(context, SvSettingKeys.VOICE_PERMISSION, R.string.sv_set_voice_permission) {
        val ctx = LocalContext.current
        var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        Preference(
            name = it.title,
            description = stringResource(if (granted) R.string.sv_set_voice_permission_granted else R.string.sv_set_voice_permission_missing),
            onClick = {
                if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO)
                else try {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null)))
                } catch (_: Exception) { }
            }
        )
    },
    Setting(context, SvSettingKeys.VOICE_ENGINE, R.string.sv_set_voice_engine) {
        val ctx = LocalContext.current
        val available = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
        val onDevice = remember { Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }
        Preference(
            name = it.title,
            description = stringResource(when {
                onDevice -> R.string.sv_set_voice_engine_ondevice
                available -> R.string.sv_set_voice_engine_ok
                else -> R.string.sv_set_voice_engine_missing
            }),
            onClick = {
                try {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (_: Exception) {
                    try { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
                }
            }
        )
    },
    Setting(context, SmartPrefs.VOICE_LANGUAGE, R.string.sv_set_voice_language) {
        val ctx = LocalContext.current
        ListPreference(it, listOf(
            ctx.getString(R.string.sv_voice_lang_auto) to "auto",
            ctx.getString(R.string.sv_voice_lang_ru) to "ru-RU",
            ctx.getString(R.string.sv_voice_lang_en) to "en-US",
        ), SmartPrefs.DEF_VOICE_LANGUAGE)
    },
    Setting(context, SmartPrefs.VOICE_SMART_PUNCTUATION, R.string.sv_set_voice_punctuation, R.string.sv_set_voice_punctuation_summary) {
        SwitchPreference(it, SmartPrefs.DEF_VOICE_SMART_PUNCTUATION)
    },
    Setting(context, SmartPrefs.VOICE_REMOVE_FILLERS, R.string.sv_set_voice_fillers, R.string.sv_set_voice_fillers_summary) {
        SwitchPreference(it, SmartPrefs.DEF_VOICE_REMOVE_FILLERS)
    },
    Setting(context, SmartPrefs.VOICE_COMMANDS, R.string.sv_set_voice_commands, R.string.sv_set_voice_commands_summary) {
        SwitchPreference(it, SmartPrefs.DEF_VOICE_COMMANDS)
    },
    Setting(context, SmartPrefs.VOICE_CONTINUOUS, R.string.sv_set_voice_continuous) {
        SwitchPreference(it, SmartPrefs.DEF_VOICE_CONTINUOUS)
    },
    Setting(context, SmartPrefs.VOICE_PREFER_OFFLINE, R.string.sv_set_voice_offline, R.string.sv_set_voice_offline_summary) {
        SwitchPreference(it, SmartPrefs.DEF_VOICE_PREFER_OFFLINE)
    },
    Setting(context, SmartPrefs.VOICE_SEND_COMMAND, R.string.sv_set_voice_send, R.string.sv_set_voice_send_summary) {
        SwitchPreference(it, SmartPrefs.DEF_VOICE_SEND_COMMAND)
    },
    Setting(context, SmartPrefs.VOICE_VOCABULARY_ENABLED, R.string.sv_voice_vocabulary_enabled, R.string.sv_voice_vocabulary_summary) {
        SwitchPreference(it, false)
    },
    Setting(context, SmartPrefs.VOICE_VOCABULARY, R.string.sv_voice_vocabulary_terms, R.string.sv_voice_vocabulary_help) { setting ->
        val prefs = LocalContext.current.prefs()
        var editing by rememberSaveable { mutableStateOf(false) }
        Preference(name = setting.title, description = setting.description, onClick = { editing = true })
        if (editing) TextInputDialog(
            onDismissRequest = { editing = false },
            title = { Text(setting.title) },
            description = { Text(stringResource(R.string.sv_voice_vocabulary_help)) },
            initialText = prefs.getString(SmartPrefs.VOICE_VOCABULARY, "").orEmpty(),
            singleLine = false,
            checkTextValid = { text ->
                val terms = text.split('\n', ';').filter { it.isNotBlank() }
                text.length <= helium314.keyboard.smart.voice.VoiceVocabulary.MAX_INPUT && terms.size <= 100 && terms.all { it.trim().length <= 80 }
            },
            onConfirmed = { text -> prefs.edit { putString(SmartPrefs.VOICE_VOCABULARY,
                helium314.keyboard.smart.voice.VoiceVocabulary.parse(text).joinToString("\n")) } },
            neutralButtonText = stringResource(R.string.sv_clip_delete),
            onNeutral = { prefs.edit { remove(SmartPrefs.VOICE_VOCABULARY) } },
        )
    },
    Setting(context, "sv_screenshot_setup", R.string.sv_screenshot_setup) {
        val ctx = LocalContext.current
        Preference(name = it.title, description = "Только новые скриншоты · разрешения Android", onClick = {
            ctx.startActivity(Intent(ctx, helium314.keyboard.smart.clipboard.ScreenshotPermissionActivity::class.java))
        })
    },
    Setting(context, SmartPrefs.CLIP_CHIP, R.string.sv_set_clip_chip, R.string.sv_set_clip_chip_summary) {
        SwitchPreference(it, SmartPrefs.DEF_CLIP_CHIP)
    },
    Setting(context, SmartPrefs.CLIP_CHIP_MINUTES, R.string.sv_set_clip_chip_minutes) {
        val ctx = LocalContext.current
        ListPreference(it, listOf(1, 2, 5, 10, 30, 60).map { m -> ctx.getString(R.string.sv_minutes, m) to m }, SmartPrefs.DEF_CLIP_CHIP_MINUTES)
    },
    Setting(context, SvSettingKeys.CLIP_SENSITIVE, R.string.sv_set_clip_sensitive, R.string.sv_set_clip_sensitive_summary) {
        Preference(name = it.title, description = it.description, onClick = { })
    },
    Setting(context, SvSettingKeys.CLIP_CLEAR, R.string.sv_set_clip_clear, R.string.sv_set_clip_clear_summary) {
        val ctx = LocalContext.current
        var confirm by rememberSaveable { mutableStateOf(false) }
        Preference(name = it.title, description = it.description, onClick = { confirm = true })
        if (confirm) ConfirmationDialog(
            onDismissRequest = { confirm = false },
            onConfirmed = {
                ClipboardDao.getInstance(ctx)?.clearNonPinned()
                toast(ctx, R.string.sv_set_clip_cleared)
            },
            content = { Text(stringResource(R.string.sv_set_clip_clear_confirm)) }
        )
    },
    Setting(context, SvSettingKeys.THEME_GALLERY, R.string.sv_set_theme) {
        ThemeGallery()
    },
    Setting(context, SvSettingKeys.THEME_MORE, R.string.sv_set_theme_more, R.string.sv_set_theme_more_summary) {
        Preference(name = it.title, description = it.description,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.Appearance) }) { NextScreenIcon() }
    },
    Setting(context, SvSettingKeys.LEARNING_CLEAR, R.string.sv_set_learning_clear) {
        val ctx = LocalContext.current
        var confirm by rememberSaveable { mutableStateOf(false) }
        var count by remember { SmartLearning.init(ctx); mutableStateOf(SmartLearning.size()) }
        Preference(name = it.title, description = stringResource(R.string.sv_set_learning_clear_summary, count), onClick = { confirm = true })
        if (confirm) ConfirmationDialog(
            onDismissRequest = { confirm = false },
            onConfirmed = {
                SmartUi.clearLearning(ctx)
                count = 0
                toast(ctx, R.string.sv_set_learning_cleared)
            },
            content = { Text(stringResource(R.string.sv_set_learning_clear_confirm)) }
        )
    },
    Setting(context, SvSettingKeys.DICTIONARIES, R.string.sv_set_dictionaries) {
        Preference(name = it.title, onClick = { SettingsDestination.navigateTo(SettingsDestination.Dictionaries) }) { NextScreenIcon() }
    },
    Setting(context, SvSettingKeys.STORAGE_INFO, R.string.sv_set_storage, R.string.sv_set_storage_summary) {
        Preference(name = it.title, description = it.description, onClick = { })
    },
    Setting(context, SvSettingKeys.ADVANCED, R.string.sv_set_advanced) {
        Preference(name = it.title, onClick = { SettingsDestination.navigateTo(SettingsDestination.Advanced) }) { NextScreenIcon() }
    },
)

@Composable
private fun ThemeGallery() {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    recomposeOnPrefChange()
    val dayNight = prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
    val current = if (dayNight) "auto" else prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FunctionsPanelView.THEMES.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    ThemeCard(t, t.id == current, Modifier.weight(1f)) {
                        prefs.edit {
                            putBoolean(Settings.PREF_THEME_DAY_NIGHT, t.dayNight)
                            putString(Settings.PREF_THEME_COLORS, t.day)
                            putString(Settings.PREF_THEME_COLORS_NIGHT, t.night)
                        }
                        KeyboardSwitcher.getInstance().setThemeNeedsReload()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ThemeCard(theme: FunctionsPanelView.ThemeChoice, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).clickable(onClick = onClick).padding(4.dp)) {
        val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(shape).border(border, shape)) {
            fun half(colors: IntArray, x0: Float, x1: Float) {
                val c = colors.map { Color(it) }
                drawRect(c[0], Offset(x0, 0f), Size(x1 - x0, size.height))
                val pad = size.width * 0.06f
                val gap = size.height * 0.05f
                val rows = 4
                val rowH = (size.height - 2 * pad - (rows - 1) * gap) / rows
                for (r in 0 until rows) {
                    val keys = if (r == rows - 1) 3 else 6
                    val keyW = (size.width - 2 * pad - (keys - 1) * gap) / keys
                    for (k in 0 until keys) {
                        val x = pad + k * (keyW + gap)
                        if (x + keyW < x0 || x > x1) continue
                        val left = maxOf(x, x0)
                        val right = minOf(x + keyW, x1)
                        val color = when {
                            r == rows - 1 && k == 2 -> c[3]
                            r == rows - 1 && k == 0 -> c[2]
                            else -> c[1]
                        }
                        drawRoundRect(color, Offset(left, pad + r * (rowH + gap)), Size(right - left, rowH), CornerRadius(rowH * 0.25f))
                    }
                }
            }
            if (theme.dayNight) {
                half(FunctionsPanelView.themeColors(theme.day), 0f, size.width / 2)
                half(FunctionsPanelView.themeColors(theme.night), size.width / 2, size.width)
            } else half(FunctionsPanelView.themeColors(theme.day), 0f, size.width)
        }
        Text(
            stringResource(theme.label),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
