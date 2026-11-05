/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.keyboard.emoji

import android.content.res.Configuration
import android.content.res.Resources
import android.view.View
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

/** Five square emoji rows on phones; the grid scrolls when available height is smaller. */
internal class EmojiLayoutParams(res: Resources) {
    private val density = res.displayMetrics.density
    val showStudioBanner = res.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    private val fixedHeight = ((if (showStudioBanner) 76 else 0) + 44 + 54 + 2 + 1) * density
    private val stripHeight = res.getDimension(R.dimen.config_suggestions_strip_height)
    private val width = ResourceUtils.getKeyboardWidth(Settings.getCurrentContext(), Settings.getValues())
    private val columns = kotlin.math.round(8f * width / (360f * density)).toInt().coerceAtLeast(4)
    val panelHeight = (width / columns * 5 + fixedHeight).toInt().coerceAtMost(
        (res.displayMetrics.heightPixels * 0.78f - stripHeight).toInt().coerceAtLeast((180 * density).toInt()))
    val emojiKeyboardHeight = (panelHeight - fixedHeight).toInt().coerceAtLeast((42 * density).toInt())
    val bottomRowKeyboardHeight = (48 * density).toInt()

    fun setEmojiListProperties(vp: ViewPager2) {
        vp.layoutParams = (vp.layoutParams as LinearLayout.LayoutParams).apply { height = emojiKeyboardHeight }
    }
    fun setCategoryPageIdViewProperties(v: View) {
        v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { height = (2 * density).toInt().coerceAtLeast(1) }
    }
}
