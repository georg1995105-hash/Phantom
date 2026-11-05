// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.smart.SmartPrefs
import java.util.Locale

/**
 * Dictation directly into the focused field through the system [SpeechRecognizer]. Partial
 * results are shown as composing text, final results are corrected locally by [SpeechCorrector]
 * and committed. Whole-utterance [VoiceCommand]s edit the text instead.
 */
class VoiceController(private val ime: LatinIME) : RecognitionListener {

    enum class State { OFF, STARTING, LISTENING, PROCESSING, PAUSED, ERROR, NEED_PERMISSION, UNAVAILABLE }

    private sealed class UndoAction {
        class Inserted(val text: String) : UndoAction()
        class Deleted(val before: String, val after: String = "") : UndoAction()
        class Replaced(val start: Int, val oldText: String, val newText: String) : UndoAction()
    }

    interface Ui {
        fun onVoiceState(state: State, message: CharSequence?)
        fun onVoiceLevel(rmsDb: Float)
        fun onVoiceLanguage(languageTag: String)
        fun onUndoAvailable(available: Boolean)
        fun setVoicePanelVisible(visible: Boolean)
    }

    var ui: Ui? = null
    var state = State.OFF
        private set
    val isActive get() = state != State.OFF

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizerIsOnDevice = false
    private var useSystemForSession = false
    private var formattedResultsRequested = false
    private var listening = false
    private var generation = 0
    private var segmentBefore: CharSequence? = null
    private var composing = ""
    private val undoStack = ArrayDeque<UndoAction>()
    private var emptyResults = 0
    private var hadSpeechInSession = false
    private var retries = 0
    private var pendingStartAfterPermission = false
    private var pauseRequested = false
    private var finishRequested = false
    var languageTag: String = "ru-RU"
        private set

    private val prefs get() = ime.prefs()
    private val connection: RichInputConnection get() = ime.svConnection()

    fun toggle() {
        if (isActive) close() else open()
    }

    fun open() {
        useSystemForSession = false
        finishRequested = false
        emptyResults = 0
        retries = 0
        ime.svPrepareForVoice()
        languageTag = resolveLanguage()
        undoStack.clear()
        hadSpeechInSession = false
        ui?.setVoicePanelVisible(true)
        ui?.onVoiceLanguage(languageTag)
        ui?.onUndoAvailable(false)
        startListening()
    }

    fun close() {
        if (!isActive) return
        setState(State.OFF)
        finishRequested = false
        stopRecognizer(cancel = true)
        finishComposing()
        setState(State.OFF)
        pendingStartAfterPermission = false
        ui?.setVoicePanelVisible(false)
        destroyRecognizer()
    }

    /**
     * Explicit user end of dictation. Request the final ASR result rather than cancelling
     * the recognizer and discarding the phrase spoken immediately before Done.
     */
    fun finishByUser() {
        if (!isActive || finishRequested) return
        finishRequested = true
        pauseRequested = true
        if (!listening) {
            finishComposingAsDictation()
            close()
            return
        }
        setState(State.PROCESSING)
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            finishComposingAsDictation()
            close()
            return
        }
        // A recognizer is allowed to omit a final callback. Never leave UI stuck forever.
        handler.postDelayed({
            if (finishRequested && state != State.OFF) {
                finishComposingAsDictation()
                close()
            }
        }, 3000L)
    }

    /** Mic button inside the panel: pause or resume. */
    fun onMicTapped() {
        when (state) {
            State.LISTENING, State.STARTING, State.PROCESSING -> {
                // stop and keep what was recognized so far
                pauseRequested = true
                runCatching { recognizer?.stopListening() }
                handler.postDelayed({ if (state != State.OFF && listening) { stopRecognizer(cancel = true); finishComposing(); setState(State.PAUSED) } }, 1500)
                setState(State.PROCESSING)
            }
            State.NEED_PERMISSION -> requestPermission()
            else -> startListening()
        }
    }

    fun switchLanguage() {
        languageTag = if (languageTag.startsWith("ru")) "en-US" else "ru-RU"
        ui?.onVoiceLanguage(languageTag)
        if (state == State.LISTENING || state == State.STARTING) {
            stopRecognizer(cancel = true)
            finishComposing()
            startListening()
        }
    }

    fun onInputFinished() {
        if (isActive) close()
    }

    fun onStartInputView() {
        if (state == State.NEED_PERMISSION && pendingStartAfterPermission && hasPermission()) {
            pendingStartAfterPermission = false
            startListening()
        }
    }

    // ---------- recognizer ----------

    private fun hasPermission() = androidx.core.content.ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        pendingStartAfterPermission = true
        try {
            ime.startActivity(Intent(ime, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            Log.w(TAG, "cannot request permission", e)
        }
    }

    private fun resolveLanguage(): String {
        val pref = prefs.getString(SmartPrefs.VOICE_LANGUAGE, SmartPrefs.DEF_VOICE_LANGUAGE) ?: "auto"
        if (pref != "auto") return pref
        val locale = ime.svCurrentLocale()
        return when (locale.language) {
            "ru" -> "ru-RU"
            "en" -> if (locale.country.isNullOrEmpty()) "en-US" else locale.toLanguageTag()
            "" -> "ru-RU"
            else -> locale.toLanguageTag()
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val preferOffline = prefs.getBoolean(SmartPrefs.VOICE_PREFER_OFFLINE, SmartPrefs.DEF_VOICE_PREFER_OFFLINE)
        val onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ime) }.getOrDefault(false)
        val defaultAvailable = runCatching { SpeechRecognizer.isRecognitionAvailable(ime) }.getOrDefault(false)
        return try {
            val systemSelected = prefs.getString(SmartPrefs.VOICE_ENGINE_MODE, "auto") == "system" || useSystemForSession
            if (defaultAvailable && systemSelected && !preferOffline) {
                recognizerIsOnDevice = false
                SpeechRecognizer.createSpeechRecognizer(ime)
            } else if (onDeviceAvailable && Build.VERSION.SDK_INT >= 31) {
                recognizerIsOnDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ime)
            } else if (defaultAvailable && !preferOffline) {
                recognizerIsOnDevice = false
                SpeechRecognizer.createSpeechRecognizer(ime)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "cannot create recognizer", e)
            null
        }
    }

    private fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ime.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 12000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
        if (prefs.getBoolean(SmartPrefs.VOICE_PREFER_OFFLINE, SmartPrefs.DEF_VOICE_PREFER_OFFLINE))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        formattedResultsRequested = Build.VERSION.SDK_INT >= 33 && options().smartPunctuation
        if (Build.VERSION.SDK_INT >= 33) {
            if (formattedResultsRequested)
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            // Never disclose the personal dictionary or field context to a remote recognizer.
            val terms = personalTerms()
            if (recognizerIsOnDevice && terms.isNotEmpty()) {
                val before = connection.getTextBeforeCursor(240, 0) ?: ""
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, VoiceVocabulary.onDeviceHints(terms, before))
            }
        }
    }

    private fun personalTerms(): List<String> {
        if (!prefs.getBoolean(SmartPrefs.VOICE_VOCABULARY_ENABLED, false)) return emptyList()
        val settings = Settings.getValues() ?: return emptyList()
        if (settings.mIncognitoModeEnabled || settings.mInputAttributes.mNoLearning ||
            settings.mInputAttributes.mIsPasswordField) return emptyList()
        return VoiceVocabulary.parse(prefs.getString(SmartPrefs.VOICE_VOCABULARY, "").orEmpty())
    }

    private fun startListening() {
        if (!hasPermission()) {
            setState(State.NEED_PERMISSION, ime.getString(R.string.sv_voice_need_permission))
            requestPermission()
            return
        }
        if (recognizer == null) {
            recognizer = createRecognizer()
            if (recognizer == null) {
                setState(State.UNAVAILABLE, ime.getString(if (prefs.getBoolean(SmartPrefs.VOICE_PREFER_OFFLINE, SmartPrefs.DEF_VOICE_PREFER_OFFLINE)) R.string.sv_voice_offline_unavailable else R.string.sv_voice_unavailable))
                return
            }
        }
        finishComposing()
        segmentBefore = null
        pauseRequested = false
        val token = ++generation
        val target = this
        recognizer?.setRecognitionListener(object : RecognitionListener {
            private fun valid() = token == generation && listening && state != State.OFF
            override fun onReadyForSpeech(b: Bundle?) { if (valid()) target.onReadyForSpeech(b) }
            override fun onBeginningOfSpeech() { if (valid()) target.onBeginningOfSpeech() }
            override fun onRmsChanged(v: Float) { if (valid()) target.onRmsChanged(v) }
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (valid()) target.onEndOfSpeech() }
            override fun onError(e: Int) { if (valid()) target.onError(e) }
            override fun onResults(b: Bundle?) { if (valid()) target.onResults(b) }
            override fun onPartialResults(b: Bundle?) { if (valid()) target.onPartialResults(b) }
            override fun onEvent(e: Int, b: Bundle?) = Unit
        })
        listening = true
        setState(State.STARTING)
        watchdog(15000)
        try {
            recognizer?.startListening(recognizerIntent())
        } catch (e: Exception) {
            stopRecognizer(cancel = true)
            Log.w(TAG, "startListening failed", e)
            setState(State.ERROR, ime.getString(R.string.sv_voice_error_generic))
        }
    }

    private fun stopRecognizer(cancel: Boolean) {
        handler.removeCallbacksAndMessages(null)
        generation++
        if (!listening) return
        listening = false
        try { if (cancel) recognizer?.cancel() else recognizer?.stopListening() } catch (_: Exception) { }
    }

    private fun destroyRecognizer() {
        generation++
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
    }

    private fun setState(newState: State, message: CharSequence? = null) {
        state = newState
        ui?.onVoiceState(newState, message)
    }

    // ---------- RecognitionListener ----------

    override fun onReadyForSpeech(params: Bundle?) {
        if (!listening || state == State.OFF || state == State.PROCESSING || pauseRequested || finishRequested) return
        watchdog(120000)
        setState(State.LISTENING)
    }

    override fun onBeginningOfSpeech() {
        if (!listening || state == State.OFF || state == State.PROCESSING || pauseRequested || finishRequested) return
        setState(State.LISTENING)
    }

    override fun onRmsChanged(rmsdB: Float) {
        ui?.onVoiceLevel(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        if (!listening || state == State.OFF || state == State.PROCESSING) return
        watchdog(8000)
        setState(State.PROCESSING)
    }

    private fun watchdog(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (listening && state != State.OFF) {
                stopRecognizer(cancel = true)
                finishComposingAsDictation()
                destroyRecognizer()
                if (finishRequested) close()
                else setState(State.PAUSED, ime.getString(R.string.sv_voice_tap_to_continue))
            }
        }, delayMs)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!listening || state == State.OFF) return
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (segmentBefore == null) segmentBefore = connection.getTextBeforeCursor(SENTENCE_CONTEXT, 0) ?: ""
        val display = SpeechCorrector.correctPartial(text, segmentBefore, options())
        if (display != composing) {
            composing = display
            connection.setComposingText(display, 1)
            // Active speech must not hit the startup watchdog merely because a provider
            // omitted onReadyForSpeech, nor a fixed two-minute deadline on a long phrase.
            if (!pauseRequested && !finishRequested && state != State.PROCESSING) watchdog(120000)
        }
    }

    override fun onResults(results: Bundle?) {
        if (!listening || state == State.OFF) return
        listening = false
        handler.removeCallbacksAndMessages(null)
        val text = SpeechHypotheses.choose(
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
            results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES),
            formattedResultsRequested, personalTerms())
        destroyRecognizer()
        if (text.isEmpty()) {
            finishComposingAsDictation()
            if (finishRequested) { close(); return }
            onNothingRecognized()
            return
        }
        emptyResults = 0
        retries = 0
        hadSpeechInSession = true
        val command = if (prefs.getBoolean(SmartPrefs.VOICE_COMMANDS, SmartPrefs.DEF_VOICE_COMMANDS)) VoiceCommand.parse(text) else null
        val handled = command != null && executeCommand(command)
        if (!handled) commitDictation(text)
        if (state == State.OFF) return
        if (finishRequested) { close(); return }
        if (!pauseRequested && command != VoiceCommand.Stop &&
            prefs.getBoolean(SmartPrefs.VOICE_CONTINUOUS, SmartPrefs.DEF_VOICE_CONTINUOUS))
            handler.postDelayed({ if (state != State.OFF && state != State.PAUSED) startListening() }, RESTART_DELAY)
        else setState(State.PAUSED)
    }

    override fun onError(error: Int) {
        if (!listening || state == State.OFF) return
        handler.removeCallbacksAndMessages(null)
        if (finishRequested) { finishComposingAsDictation(); close(); return }
        listening = false
        destroyRecognizer()
        if (pauseRequested) {
            finishComposingAsDictation()
            setState(State.PAUSED)
            return
        }
        Log.i(TAG, "recognizer error $error")
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // keep what was shown as partial result, it's better than nothing
                if (composing.isNotEmpty()) finishComposingAsDictation() else clearComposing()
                onNothingRecognized()
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                clearComposing()
                setState(State.NEED_PERMISSION, ime.getString(R.string.sv_voice_need_permission))
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                if (retries++ < 2) {
                    finishComposingAsDictation()
                    destroyRecognizer()
                    handler.postDelayed({ if (state != State.OFF) startListening() }, 300)
                } else {
                    finishComposingAsDictation()
                    setState(State.ERROR, ime.getString(R.string.sv_voice_error_busy))
                }
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                finishComposingAsDictation()
                setState(State.ERROR, ime.getString(R.string.sv_voice_error_network))
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                finishComposingAsDictation()
                if (!prefs.getBoolean(SmartPrefs.VOICE_PREFER_OFFLINE, SmartPrefs.DEF_VOICE_PREFER_OFFLINE) && recognizerIsOnDevice && runCatching { SpeechRecognizer.isRecognitionAvailable(ime) }.getOrDefault(false) && retries++ < 1) {
                    // on-device model for this language is missing, use the regular service
                    destroyRecognizer()
                    useSystemForSession = true
                    recognizer = SpeechRecognizer.createSpeechRecognizer(ime)
                    recognizerIsOnDevice = false
                    startListening()
                } else setState(State.ERROR, ime.getString(R.string.sv_voice_error_language))
            }
            else -> {
                finishComposingAsDictation()
                setState(State.ERROR, ime.getString(R.string.sv_voice_error_generic))
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun onNothingRecognized() {
        if (finishRequested) { close(); return }
        emptyResults++
        if (pauseRequested || emptyResults >= 2 ||
            !prefs.getBoolean(SmartPrefs.VOICE_CONTINUOUS, SmartPrefs.DEF_VOICE_CONTINUOUS)) {
            setState(State.PAUSED, ime.getString(if (hadSpeechInSession) R.string.sv_voice_tap_to_continue else R.string.sv_voice_not_heard))
            return
        }
        setState(State.STARTING)
        handler.postDelayed({ if (state != State.OFF && !pauseRequested) startListening() }, RESTART_DELAY)
    }

    // ---------- text editing ----------

    private fun options() = SpeechCorrector.Options(
        smartPunctuation = prefs.getBoolean(SmartPrefs.VOICE_SMART_PUNCTUATION, SmartPrefs.DEF_VOICE_SMART_PUNCTUATION),
        removeFillers = prefs.getBoolean(SmartPrefs.VOICE_REMOVE_FILLERS, SmartPrefs.DEF_VOICE_REMOVE_FILLERS),
    )

    private fun commitDictation(raw: String) {
        if (segmentBefore == null) {
            clearComposing()
            segmentBefore = connection.getTextBeforeCursor(SENTENCE_CONTEXT, 0) ?: ""
        }
        val text = SpeechCorrector.correct(raw, segmentBefore, options())
        connection.beginBatchEdit()
        connection.setComposingText(text, 1)
        connection.finishComposingText()
        connection.endBatchEdit()
        composing = ""
        segmentBefore = null
        if (text.isNotEmpty()) pushUndo(UndoAction.Inserted(text))
        ime.svAfterVoiceEdit()
    }

    private fun finishComposingAsDictation() {
        if (composing.isEmpty()) return
        val shown = composing
        connection.finishComposingText()
        composing = ""
        segmentBefore = null
        pushUndo(UndoAction.Inserted(shown))
        ime.svAfterVoiceEdit()
    }

    private fun finishComposing() {
        if (composing.isEmpty()) return
        finishComposingAsDictation()
    }

    private fun clearComposing() {
        if (composing.isEmpty()) return
        connection.setComposingText("", 1)
        connection.finishComposingText()
        composing = ""
    }

    private fun pushUndo(action: UndoAction) {
        undoStack.addLast(action)
        while (undoStack.size > 30) undoStack.removeFirst()
        ui?.onUndoAvailable(true)
    }

    /** returns false if the utterance should be dictated as text instead */
    private fun executeCommand(command: VoiceCommand): Boolean {
        clearComposing()
        val conn = connection
        when (command) {
            VoiceCommand.DeleteLastWord -> {
                val before = conn.getTextBeforeCursor(200, 0)?.toString().orEmpty()
                val n = VoiceTextOps.lastWordDeleteLength(before)
                if (n == 0) return true
                conn.deleteTextBeforeCursor(n)
                pushUndo(UndoAction.Deleted(before.takeLast(n)))
            }
            VoiceCommand.DeleteLastSentence -> {
                val before = conn.getTextBeforeCursor(3000, 0)?.toString().orEmpty()
                val n = VoiceTextOps.lastSentenceDeleteLength(before)
                if (n == 0) return true
                conn.deleteTextBeforeCursor(n)
                pushUndo(UndoAction.Deleted(before.takeLast(n)))
            }
            VoiceCommand.DeleteAll -> {
                val before = conn.getTextBeforeCursor(MAX_FIELD, 0)?.toString().orEmpty()
                val after = conn.getTextAfterCursor(MAX_FIELD, 0)?.toString().orEmpty()
                if (before.isEmpty() && after.isEmpty()) return true
                conn.beginBatchEdit()
                if (after.isNotEmpty()) ime.currentInputConnection?.deleteSurroundingText(0, after.length)
                if (before.isNotEmpty()) conn.deleteTextBeforeCursor(before.length)
                conn.endBatchEdit()
                pushUndo(UndoAction.Deleted(before, after))
            }
            VoiceCommand.Undo -> undo()
            VoiceCommand.Stop -> {
                setState(State.PAUSED, ime.getString(R.string.sv_voice_tap_to_continue))
            }
            VoiceCommand.Finish -> close()
            VoiceCommand.NewLine -> {
                conn.commitText("\n", 1)
                pushUndo(UndoAction.Inserted("\n"))
            }
            VoiceCommand.Send -> {
                if (!canSend()) return false
                ime.svAfterVoiceEdit()
                conn.performEditorAction(EditorInfo.IME_ACTION_SEND)
                close()
            }
            is VoiceCommand.Replace -> {
                val before = conn.getTextBeforeCursor(MAX_FIELD, 0)?.toString().orEmpty()
                val idx = VoiceTextOps.findLast(before, command.target)
                if (idx < 0) {
                    ui?.onVoiceState(state, ime.getString(R.string.sv_voice_not_found, command.target))
                    return true
                }
                val cursor = conn.expectedSelectionStart
                if (cursor < 0) return true
                val start = cursor - before.length + idx
                val original = before.substring(idx, idx + command.target.length)
                val replacement = VoiceTextOps.matchCase(original, command.replacement)
                conn.beginBatchEdit()
                conn.setSelection(start, start + original.length)
                conn.commitText(replacement, 1)
                val newCursor = cursor + replacement.length - original.length
                conn.setSelection(newCursor, newCursor)
                conn.endBatchEdit()
                pushUndo(UndoAction.Replaced(start, original, replacement))
            }
        }
        ime.svAfterVoiceEdit()
        return true
    }

    private fun canSend(): Boolean {
        if (!prefs.getBoolean(SmartPrefs.VOICE_SEND_COMMAND, SmartPrefs.DEF_VOICE_SEND_COMMAND)) return false
        val ei = ime.currentInputEditorInfo ?: return false
        if (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return false
        return ei.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND
    }

    fun undo() {
        clearComposing()
        val action = undoStack.removeLastOrNull()
        val conn = connection
        when (action) {
            null -> {}
            is UndoAction.Inserted -> {
                val before = conn.getTextBeforeCursor(action.text.length, 0)?.toString()
                if (before == action.text) conn.deleteTextBeforeCursor(action.text.length)
            }
            is UndoAction.Deleted -> {
                conn.beginBatchEdit()
                if (action.before.isNotEmpty()) conn.commitText(action.before, 1)
                if (action.after.isNotEmpty()) {
                    val pos = conn.expectedSelectionStart
                    conn.commitText(action.after, 1)
                    if (pos >= 0) conn.setSelection(pos, pos)
                }
                conn.endBatchEdit()
            }
            is UndoAction.Replaced -> {
                val cursor = conn.expectedSelectionStart
                conn.beginBatchEdit()
                conn.setSelection(action.start, action.start + action.newText.length)
                val selected = conn.getSelectedText(0)?.toString()
                if (selected == action.newText) {
                    conn.commitText(action.oldText, 1)
                    val newCursor = cursor - action.newText.length + action.oldText.length
                    conn.setSelection(newCursor, newCursor)
                } else if (cursor >= 0) conn.setSelection(cursor, cursor)
                conn.endBatchEdit()
            }
        }
        ui?.onUndoAvailable(undoStack.isNotEmpty())
        ime.svAfterVoiceEdit()
    }

    /** Backspace key inside the voice panel. */
    fun deleteBackward() {
        clearComposing()
        ime.svDeleteBackward()
    }

    companion object {
        private const val TAG = "VoiceController"
        private const val SENTENCE_CONTEXT = 120
        private const val MAX_FIELD = 20000
        private const val RESTART_DELAY = 120L

        fun displayLanguage(tag: String): String {
            val l = Locale.forLanguageTag(tag)
            return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
        }
    }
}
