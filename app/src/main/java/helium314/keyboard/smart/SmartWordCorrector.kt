// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.Toast
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.smart.voice.VoiceTextOps

/**
 * Explicit, user-initiated correction of ONE misspelled word via the selected
 * Android spell checker. No network endpoint, API token or grammar model is bundled.
 * Does not inspect protected inputs or silently modify whole messages.
 */
object SmartWordCorrector {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val wordRegex = Regex("""[\p{L}][\p{L}\p{M}'’\-]*""")
    private val lastWordRegex = Regex("""[\p{L}][\p{L}\p{M}'’\-]*$""")
    private var requestId = 0
    private var session: SpellCheckerSession? = null
    private data class Correction(val start: Int, val original: String, val corrected: String)
    private var previous: Correction? = null

    fun wordBeforeCursor(text: String): String? = lastWordRegex.find(text)?.value

    fun correct(ime: LatinIME) {
        val info = ime.currentInputEditorInfo ?: return
        val input = info.inputType
        val type = input and InputType.TYPE_MASK_CLASS
        val variation = input and InputType.TYPE_MASK_VARIATION
        if (type != InputType.TYPE_CLASS_TEXT ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
            // Never send passwords or non-text input to an installed spell checker.
            toast(ime, R.string.sv_correct_protected)
            return
        }
        val conn = ime.svConnection()
        val selected = conn.getSelectedText(0)?.toString()
        val cursorStart = conn.expectedSelectionStart
        val cursorEnd = conn.expectedSelectionEnd
        if (cursorStart < 0 || cursorEnd < 0) return

        val original = if (!selected.isNullOrEmpty()) {
            if (!wordRegex.matches(selected) || selected.length > 64) {
                toast(ime, R.string.sv_correct_select_word)
                return
            }
            selected
        } else wordBeforeCursor(conn.getTextBeforeCursor(80, 0)?.toString().orEmpty())
            ?: run {
                toast(ime, R.string.sv_correct_select_word)
                return
            }
        val from = if (!selected.isNullOrEmpty()) cursorStart else cursorStart - original.length
        val until = if (!selected.isNullOrEmpty()) cursorEnd else cursorStart
        if (from < 0) return

        val manager = ime.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
            ?: run { toast(ime, R.string.sv_correct_no_service); return }
        if (Build.VERSION.SDK_INT >= 31 && !manager.isSpellCheckerEnabled) {
            toast(ime, R.string.sv_correct_no_service)
            return
        }

        // Each new request invalidates callbacks from the previous one.
        cancelSession()
        val thisRequest = ++requestId
        val callback = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
                main.post {
                    if (thisRequest != requestId) return@post
                    cancelSession()
                    val suggestionInfo = results?.firstOrNull()
                    val suggested = if (suggestionInfo != null && suggestionInfo.suggestionsCount > 0 &&
                        suggestionInfo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY == 0)
                        suggestionInfo.getSuggestionAt(0) else null
                    if (suggested.isNullOrBlank() || suggested.equals(original, ignoreCase = true)) {
                        toast(ime, R.string.sv_correct_nothing)
                        return@post
                    }
                    val replacement = VoiceTextOps.matchCase(original, suggested)
                    val current = ime.svConnection()
                    if (current.expectedSelectionStart != cursorStart ||
                        current.expectedSelectionEnd != cursorEnd) {
                        return@post // The user has typed or moved the cursor since tapping Fix.
                    }
                    current.beginBatchEdit()
                    current.setSelection(from, until)
                    val stillThere = current.getSelectedText(0)?.toString() == original
                    if (stillThere) {
                        current.commitText(replacement, 1)
                        previous = Correction(from, original, replacement)
                        ime.svAfterVoiceEdit()
                    } else {
                        current.setSelection(cursorStart, cursorEnd)
                    }
                    current.endBatchEdit()
                }
            }

            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) = Unit
        }
        try {
            session = manager.newSpellCheckerSession(null, Settings.getValues().mLocale, callback, false)
            val active = session
            if (active == null) {
                toast(ime, R.string.sv_correct_no_service)
                return
            }
            active.getSuggestions(TextInfo(original), 3)
            main.postDelayed({
                if (thisRequest == requestId) {
                    cancelSession()
                    toast(ime, R.string.sv_correct_no_service)
                }
            }, 2500L)
        } catch (_: Exception) {
            cancelSession()
            toast(ime, R.string.sv_correct_no_service)
        }
    }

    fun undo(ime: LatinIME) {
        val correction = previous ?: return
        val conn = ime.svConnection()
        if (conn.expectedSelectionStart != correction.start + correction.corrected.length ||
            conn.expectedSelectionEnd != conn.expectedSelectionStart) return
        conn.beginBatchEdit()
        conn.setSelection(correction.start, correction.start + correction.corrected.length)
        if (conn.getSelectedText(0)?.toString() == correction.corrected) {
            conn.commitText(correction.original, 1)
            ime.svAfterVoiceEdit()
        }
        conn.endBatchEdit()
        previous = null
    }

    fun reset() {
        requestId++
        cancelSession()
        previous = null
    }

    private fun cancelSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    private fun toast(context: Context, res: Int) {
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
    }
}
