// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.smart.ui.SvUi
import kotlin.math.max
import kotlin.math.min

/**
 * The dictation controls live INSIDE the keyboard's preexisting suggestion strip,
 * never above the conversation and never over the typing keys.
 */
class VoicePanelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), VoiceController.Ui {

    var controller: VoiceController? = null
        set(value) { field = value; value?.ui = this }

    private val wave = ArrayList<View>(7)
    private val status = TextView(context).apply {
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun action(label: String, callback: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            contentDescription = label
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = SvUi.dp(context, 50)
            setPadding(SvUi.dp(context, 8), 0, SvUi.dp(context, 8), 0)
            setOnClickListener { callback() }
        }

    private val cancel = action("×") { controller?.close() }.apply {
        contentDescription = context.getString(android.R.string.cancel)
        textSize = 25f
    }
    private val done = action(context.getString(R.string.sv_voice_finish_recording)) {
        controller?.finishByUser()
    }

    private val language = action("RU") { controller?.switchLanguage() }.apply { minWidth = 0 }
    private val undo = SvUi.iconButton(context, R.drawable.ic_sv_reset, context.getString(R.string.sv_fn_undo), 40) { controller?.undo() }
    private val mic = SvUi.iconButton(context, R.drawable.ic_sv_mic, context.getString(R.string.sv_fn_voice), 40) { controller?.onMicTapped() }

    init {
        visibility = GONE
        isClickable = true
        isFocusable = false
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(cancel, LinearLayout.LayoutParams(SvUi.dp(context, 48), LayoutParams.MATCH_PARENT))
        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val barWidth = SvUi.dp(context, 3)
        for (i in 0..2) {
            val bar = View(context)
            val lp = LinearLayout.LayoutParams(barWidth, SvUi.dp(context, if (i % 2 == 0) 8 else 13))
            lp.marginEnd = SvUi.dp(context, 3)
            middle.addView(bar, lp)
            wave.add(bar)
        }
        middle.addView(status, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = SvUi.dp(context, 8)
        })
        row.addView(middle, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(language, LinearLayout.LayoutParams(SvUi.dp(context, 40), LayoutParams.MATCH_PARENT))
        row.addView(undo, LinearLayout.LayoutParams(SvUi.dp(context, 40), LayoutParams.MATCH_PARENT))
        row.addView(mic, LinearLayout.LayoutParams(SvUi.dp(context, 40), LayoutParams.MATCH_PARENT))
        row.addView(done, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun theme() {
        setBackgroundColor(SvUi.panelBackground())
        status.setTextColor(SvUi.text())
        cancel.setTextColor(SvUi.text())
        done.setTextColor(SvUi.text())
        language.setTextColor(SvUi.text())
        for (bar in wave) {
            bar.background = GradientDrawable().apply {
                setColor(SvUi.accent())
                cornerRadius = SvUi.dp(context, 3).toFloat()
            }
        }
    }

    override fun setVoicePanelVisible(visible: Boolean) {
        if (visible) theme()
        visibility = if (visible) VISIBLE else GONE
    }

    override fun onVoiceState(state: VoiceController.State, message: CharSequence?) {
        status.text = message ?: when (state) {
            VoiceController.State.LISTENING -> context.getString(R.string.sv_voice_listening)
            VoiceController.State.STARTING -> context.getString(R.string.sv_voice_starting)
            VoiceController.State.PROCESSING -> context.getString(R.string.sv_voice_processing)
            VoiceController.State.PAUSED -> context.getString(R.string.sv_voice_paused)
            VoiceController.State.NEED_PERMISSION -> context.getString(R.string.sv_voice_permission_title)
            VoiceController.State.UNAVAILABLE -> context.getString(R.string.sv_voice_unavailable_title)
            VoiceController.State.ERROR -> message ?: context.getString(R.string.sv_voice_error_title)
            VoiceController.State.OFF -> ""
        }
        done.isEnabled = state != VoiceController.State.PROCESSING && state != VoiceController.State.OFF
        done.alpha = if (done.isEnabled) 1f else 0.45f
    }

    override fun onVoiceLevel(rmsDb: Float) {
        // Real microphone levels change the waveform without background animation timers.
        val volume = ((rmsDb + 4f) / 17f).coerceIn(0f, 1f)
        for ((i, bar) in wave.withIndex()) {
            val height = SvUi.dp(context, (6f + volume * (11f + (i * 7 % 13))).toInt())
            val params = bar.layoutParams as LinearLayout.LayoutParams
            if (params.height != height) {
                params.height = height
                bar.layoutParams = params
            }
        }
    }

    override fun onVoiceLanguage(languageTag: String) {
        language.text = languageTag.substringBefore('-').uppercase()
        language.contentDescription = VoiceController.displayLanguage(languageTag)
    }
    override fun onUndoAvailable(available: Boolean) {
        undo.isEnabled = available
        undo.alpha = if (available) 1f else 0.4f
    }
}
