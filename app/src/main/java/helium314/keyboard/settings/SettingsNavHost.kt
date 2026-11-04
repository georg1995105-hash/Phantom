// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.getTransitionAnimationScale
import helium314.keyboard.settings.screens.AboutScreen
import helium314.keyboard.settings.screens.AdvancedSettingsScreen
import helium314.keyboard.settings.screens.AppearanceScreen
import helium314.keyboard.settings.screens.ColorsScreen
import helium314.keyboard.settings.screens.DebugScreen
import helium314.keyboard.settings.screens.DictionaryScreen
import helium314.keyboard.settings.screens.GestureTypingScreen
import helium314.keyboard.settings.screens.LanguageScreen
import helium314.keyboard.settings.screens.SvClipboardScreen
import helium314.keyboard.settings.screens.SvInputScreen
import helium314.keyboard.settings.screens.SvLayoutScreen
import helium314.keyboard.settings.screens.SvMainSettingsScreen
import helium314.keyboard.settings.screens.SvPrivacyScreen
import helium314.keyboard.settings.screens.SvThemeScreen
import helium314.keyboard.settings.screens.SvVoiceScreen
import helium314.keyboard.settings.screens.PersonalDictionariesScreen
import helium314.keyboard.settings.screens.PersonalDictionaryScreen
import helium314.keyboard.settings.screens.PreferencesScreen
import helium314.keyboard.settings.screens.SecondaryLayoutScreen
import helium314.keyboard.settings.screens.SubtypeScreen
import helium314.keyboard.settings.screens.TextCorrectionScreen
import helium314.keyboard.settings.screens.ToolbarScreen
import helium314.keyboard.settings.screens.gesturedata.GestureDataScreen
import helium314.keyboard.settings.screens.gesturedata.ReviewScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun SettingsNavHost(
    onClickBack: () -> Unit,
    startDestination: String? = null,
) {
    val navController = rememberNavController()
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val target = SettingsDestination.navTarget.collectAsState()

    // duration does not change when system setting changes, but that's rare enough to not care
    val duration = (250 * getTransitionAnimationScale(LocalContext.current)).toInt()
    val animation = tween<IntOffset>(durationMillis = duration)

    fun goBack() {
        if (!navController.popBackStack()) onClickBack()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination ?: SettingsDestination.Settings,
        enterTransition = { slideInHorizontally(initialOffsetX = { +it * dir }, animationSpec = animation) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it * dir }, animationSpec = animation) },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it * dir }, animationSpec = animation) },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { +it * dir }, animationSpec = animation) }
    ) {
        composable(SettingsDestination.Settings) {
            SvMainSettingsScreen(onNavigate = { navController.navigate(it) }, onClickBack = ::goBack)
        }
        composable(SettingsDestination.SvLayout) { SvLayoutScreen(onClickBack = ::goBack) }
        composable(SettingsDestination.SvInput) { SvInputScreen(onClickBack = ::goBack) }
        composable(SettingsDestination.SvVoice) { SvVoiceScreen(onClickBack = ::goBack) }
        composable(SettingsDestination.SvClipboard) { SvClipboardScreen(onClickBack = ::goBack) }
        composable(SettingsDestination.SvTheme) { SvThemeScreen(onClickBack = ::goBack) }
        composable(SettingsDestination.SvPrivacy) { SvPrivacyScreen(onClickBack = ::goBack) }
        composable(SettingsDestination.About) {
            AboutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.TextCorrection) {
            TextCorrectionScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Preferences) {
            PreferencesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Toolbar) {
            ToolbarScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.GestureTyping) {
            GestureTypingScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.DataGathering) {
            GestureDataScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.DataReview) {
            ReviewScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Advanced) {
            AdvancedSettingsScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Debug) {
            DebugScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Appearance) {
            AppearanceScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.PersonalDictionary + "{locale}") {
            val locale = it.arguments?.getString("locale")?.takeIf { loc -> loc.isNotBlank() }?.constructLocale()
            PersonalDictionaryScreen(
                onClickBack = ::goBack,
                locale = locale
            )
        }
        composable(SettingsDestination.PersonalDictionaries) {
            PersonalDictionariesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Languages) {
            LanguageScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Dictionaries) {
            DictionaryScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Layouts) {
            SecondaryLayoutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Colors + "{theme}") {
            ColorsScreen(isNight = false, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.ColorsNight + "{theme}") {
            ColorsScreen(isNight = true, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.Subtype + "{subtype}") {
            SubtypeScreen(initialSubtype = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
    }
    if (target.value != SettingsDestination.Settings/* && target.value != navController.currentBackStackEntry?.destination?.route*/)
        navController.navigate(route = target.value)
}

object SettingsDestination {
    const val Settings = "settings"
    const val About = "about"
    const val TextCorrection = "text_correction"
    const val Preferences = "preferences"
    const val Toolbar = "toolbar"
    const val GestureTyping = "gesture_typing"
    const val DataGathering = "data_gathering" // remove when data gathering phase is done (end of 2026 latest)
    const val DataReview = "data_review" // remove when data gathering phase is done (end of 2026 latest)
    const val Advanced = "advanced"
    const val Debug = "debug"
    const val Appearance = "appearance"
    const val Colors = "colors/"
    const val ColorsNight = "colors_night/"
    const val PersonalDictionaries = "personal_dictionaries"
    const val PersonalDictionary = "personal_dictionary/"
    const val Languages = "languages"
    const val Subtype = "subtype/"
    const val Layouts = "layouts"
    const val Dictionaries = "dictionaries"
    const val SvLayout = "sv_layout"
    const val SvInput = "sv_input"
    const val SvVoice = "sv_voice"
    const val SvClipboard = "sv_clipboard"
    const val SvTheme = "sv_theme"
    const val SvPrivacy = "sv_privacy"

    /** Maps the screen names used by the keyboard (see LatinIME.svLaunchSettings) to destinations. */
    fun forSvScreen(screen: String?): String? = when (screen) {
        "voice" -> SvVoice
        "theme" -> SvTheme
        "languages" -> Languages
        "clipboard" -> SvClipboard
        "layout" -> SvLayout
        "input" -> SvInput
        "privacy" -> SvPrivacy
        else -> null
    }
    val navTarget = MutableStateFlow(Settings)

    private val navScope = CoroutineScope(Dispatchers.Default)
    fun navigateTo(target: String) {
        if (navTarget.value == target) {
            // triggers recompose twice, but that's ok as it's a rare event
            navTarget.value = Settings
            navScope.launch { delay(10); navTarget.value = target }
        } else
            navTarget.value = target
        navScope.launch { delay(50); navTarget.value = Settings }
    }
}
