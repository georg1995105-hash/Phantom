// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import helium314.keyboard.latin.R

/** Entry to the real local compositor; deliberately makes no AI-generation claim. */
class EmojiStudioBanner @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        gravity = Gravity.CENTER_VERTICAL
        val r = SvUi.dp(context, 18).toFloat()
        background = SvUi.ripple(SvUi.surface(if (SvUi.isDark()) 0xFF26324D.toInt() else SvUi.key(), r), r)
        setPadding(SvUi.dp(context, 12), 0, SvUi.dp(context, 10), 0)
        addView(SvUi.label(context, 31f, SvUi.accent()).apply { text = "✦" }, LayoutParams(SvUi.dp(context, 40), -1))
        val labels = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        labels.addView(SvUi.label(context, 15f, bold = true).apply { setText(R.string.sv_sticker_studio); gravity = Gravity.START })
        labels.addView(SvUi.label(context, 11f, SvUi.secondaryText()).apply { setText(R.string.sv_sticker_banner); gravity = Gravity.START })
        addView(labels, LayoutParams(0, -1, 1f))
        addView(SvUi.label(context, 39f).apply { text = "😺"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(SvUi.dp(context, 48), -1))
        addView(SvUi.label(context, 29f).apply {
            text = "+"; background = SvUi.surface(0xFF465C8D.toInt(), SvUi.dp(context, 12).toFloat())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(SvUi.dp(context, 34), SvUi.dp(context, 34)))
        contentDescription = context.getString(R.string.sv_sticker_create)
        setOnClickListener { SmartUi.openStickerStudio(false) }
    }
}
