// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.content.SharedPreferences
import androidx.core.content.edit

/** Explicit opt-in; legacy user pins remain usable without adding a default extra key. */
object BottomNumpad {
    enum class Mode { OFF, EXTRA, REPLACE_SYMBOLS }

    fun get(prefs: SharedPreferences): Mode = when (prefs.getString(SmartPrefs.BOTTOM_NUMPAD_MODE, null)) {
        "off" -> Mode.OFF
        "extra" -> Mode.EXTRA
        "replace_symbols" -> Mode.REPLACE_SYMBOLS
        else -> if (prefs.getBoolean(SmartPrefs.BOTTOM_NUMPAD, false)) Mode.EXTRA else Mode.OFF
    }

    fun set(prefs: SharedPreferences, mode: Mode) = prefs.edit {
        putString(SmartPrefs.BOTTOM_NUMPAD_MODE, when (mode) {
            Mode.OFF -> "off"
            Mode.EXTRA -> "extra"
            Mode.REPLACE_SYMBOLS -> "replace_symbols"
        })
        putBoolean(SmartPrefs.BOTTOM_NUMPAD, mode == Mode.EXTRA)
    }
}
