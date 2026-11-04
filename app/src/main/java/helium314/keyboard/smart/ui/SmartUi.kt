// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.view.View
import androidx.core.view.isVisible
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.smart.voice.VoicePanelView

/** Coordinates the SmartVoice overlays (functions menu, dictation, resize) of the current input view. */
object SmartUi {
    private var ime: LatinIME? = null
    private var voicePanel: VoicePanelView? = null
    private var functionsPanel: FunctionsPanelView? = null
    private var resizeOverlay: ResizeOverlayView? = null

    @get:JvmStatic
    var isResizing = false
        private set

    /** Set while the full clipboard is shown, handles the system back gesture there. */
    var clipboardBack: (() -> Boolean)? = null

    @JvmStatic
    fun attach(root: View, latinIme: LatinIME) {
        ime = latinIme
        voicePanel = root.findViewById<VoicePanelView>(R.id.sv_voice_panel)?.also { it.controller = latinIme.svVoice() }
        functionsPanel = root.findViewById<FunctionsPanelView>(R.id.sv_functions_panel)?.also { it.ime = latinIme }
        resizeOverlay = root.findViewById<ResizeOverlayView>(R.id.sv_resize_overlay)?.also { it.ime = latinIme }
        if (latinIme.svVoice().isActive) voicePanel?.setVoicePanelVisible(true)
        if (isResizing) resizeOverlay?.post { resizeOverlay?.show() }
    }

    @JvmStatic
    val isFunctionsShown get() = functionsPanel?.isVisible == true

    @JvmStatic
    val isVoiceShown get() = voicePanel?.isVisible == true

    @JvmStatic
    fun toggleFunctions() {
        val panel = functionsPanel ?: return
        if (panel.isVisible) panel.hide()
        else {
            ime?.svVoice()?.close()
            panel.show()
        }
    }

    @JvmStatic
    fun hideFunctions() {
        functionsPanel?.hide()
    }

    @JvmStatic
    fun startResize() {
        hideFunctions()
        ime?.svVoice()?.close()
        isResizing = true
        resizeOverlay?.show()
    }

    @JvmStatic
    fun finishResize() {
        isResizing = false
        resizeOverlay?.hide()
    }

    /** Called when the keyboard is hidden or the input field changes. */
    @JvmStatic
    fun onFinishInputView() {
        hideFunctions()
        if (isResizing) finishResize()
    }

    /** Deletes everything learned from typing; the personal dictionary is kept. */
    @JvmStatic
    fun clearLearning(context: android.content.Context) {
        helium314.keyboard.smart.SmartLearning.init(context)
        helium314.keyboard.smart.SmartLearning.clear()
        helium314.keyboard.latin.personalization.PersonalizationHelper.removeAllUserHistoryDictionaries(context)
        ime?.svClearUserHistory()
    }

    /** System back: close the topmost SmartVoice layer. */
    @JvmStatic
    fun onBack(): Boolean {
        if (isResizing) { finishResize(); return true }
        val voice = ime?.svVoice()
        if (voice != null && voice.isActive) { voice.close(); return true }
        functionsPanel?.let { if (it.isVisible) { it.onBack(); return true } }
        if (helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().isShowingClipboardHistory)
            clipboardBack?.let { return it() }
        return false
    }
}
