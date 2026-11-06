// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.view.View
import androidx.core.view.isVisible
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.smart.voice.VoicePanelView
import helium314.keyboard.smart.SmartWordCorrector

/** Coordinates the SmartVoice overlays (functions menu, dictation, resize) of the current input view. */
object SmartUi {
    private var emojiSearch: View? = null
    private var inlinePanel: View? = null
    private var ime: LatinIME? = null
    private var voicePanel: VoicePanelView? = null
    private var functionsPanel: FunctionsPanelView? = null
    private var bottomEditor: BottomRowEditorView? = null
    private var resizeOverlay: ResizeOverlayView? = null

    @get:JvmStatic
    var isResizing = false
        private set

    /** Set while the full clipboard is shown, handles the system back gesture there. */
    var clipboardBack: (() -> Boolean)? = null

    @JvmStatic
    fun attach(root: View, latinIme: LatinIME) {
        closeEmojiSearch()
        closeInlinePanel()
        ime = latinIme
        voicePanel = root.findViewById<VoicePanelView>(R.id.sv_voice_panel)?.also { it.controller = latinIme.svVoice() }
        functionsPanel = root.findViewById<FunctionsPanelView>(R.id.sv_functions_panel)?.also { it.ime = latinIme }
        bottomEditor = root.findViewById(R.id.sv_bottom_editor)
        resizeOverlay = root.findViewById<ResizeOverlayView>(R.id.sv_resize_overlay)?.also { it.ime = latinIme }
        if (latinIme.svVoice().isActive) voicePanel?.setVoicePanelVisible(true)
        if (isResizing) resizeOverlay?.post { resizeOverlay?.show() }
    }

    @JvmStatic val isEmojiSearchShown get() = emojiSearch != null
    @JvmStatic val isInlinePanelShown get() = inlinePanel != null

    @JvmStatic fun openInlinePanel(view: View) {
        closeEmojiSearch()
        closeInlinePanel()
        val parent = functionsPanel?.parent as? android.widget.FrameLayout ?: return
        inlinePanel = view.also {
            it.tag = "sv_inline_panel"
            it.isClickable = true
            parent.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }

    @JvmStatic fun openGifLibrary() {
        val service = ime ?: return
        openInlinePanel(StickerStudioView(service, library = true, gifs = true))
    }

    @JvmStatic fun closeInlinePanel() {
        inlinePanel?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        inlinePanel = null
    }

    @JvmStatic fun openStickerStudio(library: Boolean) {
        val service = ime ?: return
        openInlinePanel(StickerStudioView(service, library))
    }

    @JvmStatic
    val isFunctionsShown get() = functionsPanel?.isVisible == true

    @JvmStatic
    val isVoiceShown get() = voicePanel?.isVisible == true

    @JvmStatic
    val isBottomEditorShown get() = bottomEditor?.isVisible == true

    @JvmStatic
    fun openBottomRowEditor() {
        hideFunctions()
        ime?.svVoice()?.close()
        bottomEditor?.show()
    }

    @JvmStatic
    fun hideBottomRowEditor() {
        bottomEditor?.hide()
    }

    @JvmStatic
    fun refreshBottomRow() {
        val settings = helium314.keyboard.latin.settings.Settings.getInstance()
        val active = settings.current
        settings.loadSettings(ime ?: return, active.mLocale, active.mInputAttributes)
        helium314.keyboard.keyboard.KeyboardLayoutSet.onKeyboardThemeChanged()
        helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().reloadMainKeyboard()
    }

    @JvmStatic
    fun toggleFunctions() {
        hideBottomRowEditor()
        val panel = functionsPanel ?: return
        if (panel.isVisible) panel.hide()
        else {
            ime?.svVoice()?.close()
            panel.show()
        }
    }

    @JvmStatic
    fun openEmojiSearch(keys: List<helium314.keyboard.keyboard.Key>, callback: helium314.keyboard.keyboard.emoji.EmojiViewCallback) {
        closeInlinePanel()
        closeEmojiSearch()
        val parent = functionsPanel?.parent as? android.widget.FrameLayout ?: return
        emojiSearch = InlineEmojiSearchView(parent.context, keys, callback) { closeEmojiSearch() }.also {
            it.tag = "sv_emoji_search"
            parent.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }

    @JvmStatic fun closeEmojiSearch() {
        emojiSearch?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        emojiSearch = null
    }

    @JvmStatic
    fun openClipboardSearch() {
        ime?.let { helium314.keyboard.smart.clipboard.ClipboardSearchActivity.open(it) }
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

    /** Apply a conservative spelling suggestion to the last word, never the whole message. */
    @JvmStatic
    fun correctCurrentWord() {
        ime?.let { SmartWordCorrector.correct(it) }
    }

    @JvmStatic
    fun undoLastCorrection() {
        ime?.let { SmartWordCorrector.undo(it) }
    }

    /** Called when the keyboard is hidden or the input field changes. */
    @JvmStatic
    fun onFinishInputView() {
        closeInlinePanel()
        closeEmojiSearch()
        hideBottomRowEditor()
        SmartWordCorrector.reset()
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
        if (inlinePanel != null) { closeInlinePanel(); return true }
        if (emojiSearch != null) { closeEmojiSearch(); return true }
        if (isBottomEditorShown) { hideBottomRowEditor(); return true }
        if (isResizing) { finishResize(); return true }
        val voice = ime?.svVoice()
        if (voice != null && voice.isActive) { voice.close(); return true }
        functionsPanel?.let { if (it.isVisible) { it.onBack(); return true } }
        if (helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().isShowingClipboardHistory)
            clipboardBack?.let { return it() }
        return false
    }
}
