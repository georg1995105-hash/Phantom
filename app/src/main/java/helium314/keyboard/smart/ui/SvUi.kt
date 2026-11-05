// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings
import kotlin.math.roundToInt

/** Small helpers so the SmartVoice panels follow the active keyboard theme. */
object SvUi {
    fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).roundToInt()
    fun dp(context: Context, v: Int) = dp(context, v.toFloat())

    val colors: Colors get() = Settings.getValues().mColors
    fun text() = opaque(colors.get(ColorType.KEY_TEXT))
    fun secondaryText() = ColorUtils.setAlphaComponent(text(), 0xA0)
    fun key() = colors.get(ColorType.KEY_BACKGROUND)
    fun functional() = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
    fun accent() = opaque(colors.get(ColorType.ACTION_KEY_BACKGROUND))
    fun onAccent() = opaque(colors.get(ColorType.ACTION_KEY_ICON))
    fun strip() = colors.get(ColorType.STRIP_BACKGROUND)

    /** solid background color usable for overlays, never fully transparent */
    fun panelBackground(): Int {
        val c = colors.get(ColorType.MAIN_BACKGROUND)
        return if (Color.alpha(c) < 0xF0) {
            val base = if (ColorUtils.calculateLuminance(text()) > 0.5) Color.rgb(0x20, 0x21, 0x24) else Color.rgb(0xEE, 0xF0, 0xF3)
            ColorUtils.compositeColors(c, base)
        } else c
    }

    private fun opaque(c: Int) = if (Color.alpha(c) == 0) Color.GRAY else ColorUtils.setAlphaComponent(c, 0xFF)

    fun isDark() = ColorUtils.calculateLuminance(panelBackground()) < 0.5

    fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

    /** Subtle graphite depth and hairline edge shared by all approved panels. */
    fun surface(color: Int = key(), radius: Float = 18f): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(ColorUtils.blendARGB(color, text(), 0.055f), ColorUtils.blendARGB(color, Color.BLACK, 0.12f))
    ).apply {
        cornerRadius = radius
        setStroke(dp(Settings.getCurrentContext(), 0.6f).coerceAtLeast(1), ColorUtils.blendARGB(color, text(), 0.18f))
    }

    fun ripple(content: Drawable?, radius: Float): RippleDrawable {
        val mask = rounded(Color.WHITE, radius)
        return RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(text(), 0x33)), content, mask)
    }

    fun icon(context: Context, res: Int, color: Int = text()): Drawable? =
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(color) }

    fun iconButton(context: Context, res: Int, description: CharSequence, sizeDp: Int = 48, onClick: (View) -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageDrawable(icon(context, res))
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(null, dp(context, sizeDp) / 2f)
            minimumWidth = dp(context, sizeDp)
            minimumHeight = dp(context, sizeDp)
            setOnClickListener { haptic(it); onClick(it) }
        }

    fun label(context: Context, sizeSp: Float, color: Int = text(), bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        if (bold) paint.isFakeBoldText = true
        includeFontPadding = false
    }

    fun chip(context: Context, text: CharSequence, filled: Boolean = false) = label(context, 14f, if (filled) onAccent() else text()).apply {
        this.text = text
        val r = dp(context, 18).toFloat()
        background = ripple(surface(if (filled) accent() else key(), r), r)
        setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
    }

    @SuppressLint("ClickableViewAccessibility")
    fun haptic(view: View) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
    }
}
