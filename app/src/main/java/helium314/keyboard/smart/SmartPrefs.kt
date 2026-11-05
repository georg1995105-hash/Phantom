// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.content.SharedPreferences

/** Preference keys and defaults for SmartVoice specific features. */
object SmartPrefs {
    const val VOICE_LANGUAGE = "sv_voice_language" // "auto", "ru-RU", "en-US"
    const val VOICE_SMART_PUNCTUATION = "sv_voice_smart_punctuation"
    const val VOICE_REMOVE_FILLERS = "sv_voice_remove_fillers"
    const val VOICE_COMMANDS = "sv_voice_commands"
    const val VOICE_SEND_COMMAND = "sv_voice_send_command"
    const val VOICE_PREFER_OFFLINE = "sv_voice_prefer_offline"
    const val VOICE_CONTINUOUS = "sv_voice_continuous"

    const val CLIP_CHIP = "sv_clip_chip"
    const val CLIP_CHIP_MINUTES = "sv_clip_chip_minutes"

    const val FUNCTIONS_ORDER = "sv_functions_order"
    const val FUNCTIONS_HIDDEN = "sv_functions_hidden"

    const val DEF_VOICE_LANGUAGE = "auto"
    const val DEF_VOICE_SMART_PUNCTUATION = true
    const val DEF_VOICE_REMOVE_FILLERS = true
    const val DEF_VOICE_COMMANDS = true
    const val DEF_VOICE_SEND_COMMAND = false
    const val DEF_VOICE_PREFER_OFFLINE = false
    const val DEF_VOICE_CONTINUOUS = true
    const val DEF_CLIP_CHIP = true
    const val DEF_CLIP_CHIP_MINUTES = 10

    fun SharedPreferences.bool(key: String, def: Boolean) = getBoolean(key, def)
}
