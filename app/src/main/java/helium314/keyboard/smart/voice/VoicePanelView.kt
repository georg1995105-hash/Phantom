// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import helium314.keyboard.smart.ui.SvUi
import kotlin.math.max

/** "Слушаю" state of the keyboard: shown on top of the keys while dictating. */
class VoicePanelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), VoiceController.Ui {

    var controller: VoiceController? = null
        set(value) { field = value; value?.ui = this }

    private val langChip = SvUi.chip(context, "")
    private val status = SvUi.label(context, 18f)
    private val hint = SvUi.label(context, 12.5f)
    private val mic = MicView(context)
    private val undoChip = SvUi.chip(context, context.getString(R.string.sv_voice_undo))
    private val keyboardButton = SvUi.iconButton(context, R.drawable.ic_sv_keyboard, context.getString(R.string.sv_voice_back_to_keyboard)) { controller?.close() }
    private val deleteButton = SvUi.iconButton(context, R.drawable.sym_keyboard_delete_rounded, context.getString(R.string.sv_delete)) { }
    private val handler = Handler(Looper.getMainLooper())

    init {
        isClickable = true
        isFocusable = false
        visibility = GONE
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        langChip.setOnClickListener { SvUi.haptic(it); controller?.switchLanguage() }
        langChip.contentDescription = context.getString(R.string.sv_voice_switch_language)
        column.addView(langChip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = SvUi.dp(context, 10) })
        column.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = SvUi.dp(context, 10) })
        mic.setOnClickListener { SvUi.haptic(it); controller?.onMicTapped() }
        mic.contentDescription = context.getString(R.string.sv_voice_mic)
        val micSize = SvUi.dp(context, 112)
        column.addView(mic, LinearLayout.LayoutParams(micSize, micSize).apply { topMargin = SvUi.dp(context, 4) })
        hint.maxLines = 2
        hint.ellipsize = TextUtils.TruncateAt.END
        val side = SvUi.dp(context, 64)
        column.addView(hint, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = side; rightMargin = side })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val pad = SvUi.dp(context, 8)
        addView(keyboardButton, LayoutParams(SvUi.dp(context, 52), SvUi.dp(context, 52), Gravity.BOTTOM or Gravity.START).apply { setMargins(pad, pad, pad, pad) })
        addView(deleteButton, LayoutParams(SvUi.dp(context, 52), SvUi.dp(context, 52), Gravity.BOTTOM or Gravity.END).apply { setMargins(pad, pad, pad, pad) })
        addView(undoChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = SvUi.dp(context, 14) })
        undoChip.setCompoundDrawablesRelativeWithIntrinsicBounds(SvUi.icon(context, R.drawable.ic_undo_rounded), null, null, null)
        undoChip.compoundDrawablePadding = SvUi.dp(context, 6)
        undoChip.setOnClickListener { SvUi.haptic(it); controller?.undo() }
        undoChip.isVisible = false
        setupDeleteRepeat()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDeleteRepeat() {
        val repeater = object : Runnable {
            override fun run() {
                controller?.deleteBackward()
                handler.postDelayed(this, 60)
            }
        }
        deleteButton.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    SvUi.haptic(v)
                    controller?.deleteBackward()
                    handler.postDelayed(repeater, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeater)
                }
            }
            true
        }
    }

    // Must never make the keyboard taller: the real size comes from the parent's EXACTLY pass.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            return
        }
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val micSize = (available - SvUi.dp(context, 150)).coerceIn(SvUi.dp(context, 64), SvUi.dp(context, 112))
        val lp = mic.layoutParams
        if (lp.height != micSize) { lp.width = micSize; lp.height = micSize }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun applyTheme() {
        setBackgroundColor(SvUi.panelBackground())
        status.setTextColor(SvUi.text())
        hint.setTextColor(SvUi.secondaryText())
        mic.invalidate()
    }

    override fun setVoicePanelVisible(visible: Boolean) {
        if (visible) applyTheme()
        visibility = if (visible) VISIBLE else GONE
    }

    override fun onVoiceState(state: VoiceController.State, message: CharSequence?) {
        mic.active = state == VoiceController.State.LISTENING || state == VoiceController.State.STARTING
        mic.dimmed = state == VoiceController.State.ERROR || state == VoiceController.State.UNAVAILABLE
        if (!mic.active) mic.level = 0f
        status.text = when (state) {
            VoiceController.State.STARTING -> context.getString(R.string.sv_voice_starting)
            VoiceController.State.LISTENING -> context.getString(R.string.sv_voice_listening)
            VoiceController.State.PROCESSING -> context.getString(R.string.sv_voice_processing)
            VoiceController.State.PAUSED -> context.getString(R.string.sv_voice_paused)
            VoiceController.State.NEED_PERMISSION -> context.getString(R.string.sv_voice_permission_title)
            VoiceController.State.UNAVAILABLE -> context.getString(R.string.sv_voice_unavailable_title)
            VoiceController.State.ERROR -> context.getString(R.string.sv_voice_error_title)
            VoiceController.State.OFF -> ""
        }
        hint.text = message ?: when (state) {
            VoiceController.State.LISTENING, VoiceController.State.STARTING -> context.getString(R.string.sv_voice_hint_commands)
            else -> context.getString(R.string.sv_voice_tap_mic)
        }
        mic.invalidate()
    }

    override fun onVoiceLevel(rmsDb: Float) {
        val target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        mic.level = max(target, mic.level * 0.8f)
        mic.invalidate()
    }

    override fun onVoiceLanguage(languageTag: String) {
        langChip.text = VoiceController.displayLanguage(languageTag)
        langChip.setCompoundDrawablesRelativeWithIntrinsicBounds(SvUi.icon(context, R.drawable.ic_settings_languages), null, null, null)
        langChip.compoundDrawablePadding = SvUi.dp(context, 6)
    }

    override fun onUndoAvailable(available: Boolean) {
        undoChip.isVisible = available
    }

    private class MicView(context: Context) : View(context) {
        var level = 0f
        var active = false
        var dimmed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = ContextCompat.getDrawable(context, R.drawable.ic_sv_mic)!!.mutate()

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width * 0.30f
            val accent = SvUi.accent()
            if (active) {
                paint.color = ColorUtils.setAlphaComponent(accent, 0x38)
                canvas.drawCircle(cx, cy, r * (1.12f + 0.55f * level), paint)
                paint.color = ColorUtils.setAlphaComponent(accent, 0x60)
                canvas.drawCircle(cx, cy, r * (1.04f + 0.25f * level), paint)
            }
            paint.color = when {
                dimmed -> ColorUtils.setAlphaComponent(SvUi.text(), 0x40)
                active -> accent
                else -> SvUi.key().let { if (android.graphics.Color.alpha(it) < 0x40) ColorUtils.setAlphaComponent(SvUi.text(), 0x30) else it }
            }
            canvas.drawCircle(cx, cy, r, paint)
            icon.setTint(if (active) SvUi.onAccent() else SvUi.text())
            val s = (r * 0.62f).toInt()
            icon.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            icon.draw(canvas)
        }
    }
}
