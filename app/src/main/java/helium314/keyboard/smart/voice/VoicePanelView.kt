// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.smart.ui.SvUi

/**
 * Compact dictation status line OVER the suggestion strip; never replaces the letter keys.
 * The dedicated Done control waits for the recognizer's final result before dismissing.
 */
class VoicePanelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), VoiceController.Ui {

    var controller: VoiceController? = null
        set(value) { field = value; value?.ui = this }

    private val status = TextView(context).apply {
        textSize = 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val done = TextView(context).apply {
        text = context.getString(R.string.sv_voice_finish_recording)
        textSize = 15f
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.sv_voice_finish_recording)
        setPadding(SvUi.dp(context, 14), 0, SvUi.dp(context, 14), 0)
        setOnClickListener { controller?.finishByUser() }
    }

    init {
        visibility = GONE
        isClickable = true
        isFocusable = false
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(SvUi.dp(context, 12), 0, SvUi.dp(context, 4), 0)
            addView(status, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(done, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun applyTheme() {
        setBackgroundColor(SvUi.panelBackground())
        status.setTextColor(SvUi.text())
        done.setTextColor(SvUi.accent())
    }

    override fun setVoicePanelVisible(visible: Boolean) {
        if (visible) applyTheme()
        visibility = if (visible) VISIBLE else GONE
    }

    override fun onVoiceState(state: VoiceController.State, message: CharSequence?) {
        status.text = message ?: when (state) {
            VoiceController.State.STARTING -> context.getString(R.string.sv_voice_starting)
            VoiceController.State.LISTENING -> context.getString(R.string.sv_voice_listening)
            VoiceController.State.PROCESSING -> context.getString(R.string.sv_voice_processing)
            VoiceController.State.PAUSED -> context.getString(R.string.sv_voice_paused)
            VoiceController.State.ERROR -> context.getString(R.string.sv_voice_error_title)
            VoiceController.State.NEED_PERMISSION -> context.getString(R.string.sv_voice_permission_title)
            VoiceController.State.UNAVAILABLE -> context.getString(R.string.sv_voice_unavailable_title)
            VoiceController.State.OFF -> ""
        }
        done.isEnabled = state != VoiceController.State.OFF && state != VoiceController.State.PROCESSING
        done.alpha = if (done.isEnabled) 1f else 0.5f
    }

    override fun onVoiceLevel(rmsDb: Float) = Unit
    override fun onVoiceLanguage(languageTag: String) = Unit
    override fun onUndoAvailable(available: Boolean) = Unit
}
