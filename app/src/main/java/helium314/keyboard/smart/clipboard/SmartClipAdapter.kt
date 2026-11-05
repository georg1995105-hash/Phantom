// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.clipboard

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.R
import helium314.keyboard.smart.ui.SvUi

/** Clipboard history grid: optional section headers, text/link cards and large photo tiles in two columns. */
class SmartClipAdapter(private val callbacks: Callbacks) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onClipClick(entry: ClipboardHistoryEntry)
        fun onClipLongClick(entry: ClipboardHistoryEntry)
        fun onClipMenu(entry: ClipboardHistoryEntry, anchor: View) = onClipLongClick(entry)
    }

    sealed class Row {
        data class Header(val title: Int, val label: String? = null) : Row()
        data class Clip(val entry: ClipboardHistoryEntry) : Row()
    }

    var rows: List<Row> = emptyList()
    val selected = LinkedHashSet<Long>()
    val expanded = HashSet<Long>()
    var selectionMode = false
    var columnWidth = 0
    var textSizePx = 0f

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (val r = rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Clip -> if (SmartClipboard.kindOf(r.entry) == ClipKind.IMAGE) TYPE_IMAGE else TYPE_TEXT
    }

    override fun getItemId(position: Int) = when (val r = rows[position]) {
        is Row.Header -> Long.MIN_VALUE + (r.label?.hashCode()?.toLong() ?: r.title.toLong())
        is Row.Clip -> r.entry.id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        if (viewType == TYPE_HEADER) {
            val tv = SvUi.label(context, 12.5f, SvUi.secondaryText(), bold = true).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(SvUi.dp(context, 10), SvUi.dp(context, 8), SvUi.dp(context, 10), SvUi.dp(context, 2))
            }
            tv.layoutParams = StaggeredGridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { isFullSpan = true }
            return HeaderHolder(tv)
        }
        return ClipHolder(context, viewType == TYPE_IMAGE)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).text.apply { if (r.label != null) text = r.label else setText(r.title) }
            is Row.Clip -> (holder as ClipHolder).bind(r.entry)
        }
    }

    private class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private inner class ClipHolder(context: Context, val isImage: Boolean) : RecyclerView.ViewHolder(FrameLayout(context)) {
        private val card = itemView as FrameLayout
        private val image = ImageView(context)
        private val text = TextView(context)
        private val footer = TextView(context)
        private val pin = ImageView(context)
        private val check = ImageView(context)
        private val radius = SvUi.dp(context, 14).toFloat()
        private var entry: ClipboardHistoryEntry? = null

        init {
            val m = SvUi.dp(context, 4)
            card.layoutParams = StaggeredGridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(m, m, m, m) }
            card.clipToOutline = true
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            if (isImage) {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                card.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SvUi.dp(context, 120)))
            } else {
                text.setTextColor(SvUi.text())
                text.ellipsize = TextUtils.TruncateAt.END
                val p = SvUi.dp(context, 12)
                text.setPadding(p, SvUi.dp(context, 10), p, SvUi.dp(context, 10))
                text.compoundDrawablePadding = SvUi.dp(context, 6)
                card.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            footer.apply {
                textSize = 11f
                maxLines = 1
                setTextColor(if (isImage) Color.WHITE else SvUi.secondaryText())
                setPadding(SvUi.dp(context, 12), SvUi.dp(context, 5), SvUi.dp(context, 10), SvUi.dp(context, 7))
                if (isImage) setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, 0xA0))
            }
            card.addView(footer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            val badge = SvUi.dp(context, 22)
            val badgeBg = if (isImage) ColorUtils.setAlphaComponent(Color.BLACK, 0x66) else Color.TRANSPARENT
            pin.setImageDrawable(SvUi.icon(context, R.drawable.ic_sv_pin, if (isImage) Color.WHITE else SvUi.accent()))
            pin.scaleType = ImageView.ScaleType.CENTER_INSIDE
            pin.setPadding(SvUi.dp(context, 3), SvUi.dp(context, 3), SvUi.dp(context, 3), SvUi.dp(context, 3))
            pin.background = SvUi.rounded(badgeBg, badge / 2f)
            card.addView(pin, FrameLayout.LayoutParams(badge, badge, Gravity.TOP or Gravity.END).apply { setMargins(m, m, m, m) })
            check.scaleType = ImageView.ScaleType.CENTER_INSIDE
            card.addView(check, FrameLayout.LayoutParams(badge, badge, Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, m) })
            card.setOnClickListener { entry?.let { callbacks.onClipClick(it) } }
            card.setOnLongClickListener { entry?.let { callbacks.onClipLongClick(it) }; true }
            val menu = SvUi.iconButton(context, R.drawable.ic_sv_more_vert, context.getString(R.string.sv_clip_select), 32) {
                entry?.let { callbacks.onClipMenu(it, card) }
            }
            if (isImage) {
                menu.setColorFilter(Color.WHITE)
                menu.background = SvUi.ripple(SvUi.rounded(0xE610131C.toInt(), SvUi.dp(context, 16).toFloat()), SvUi.dp(context, 16).toFloat())
            }
            card.addView(menu, FrameLayout.LayoutParams(SvUi.dp(context, 32), SvUi.dp(context, 32), Gravity.TOP or Gravity.END))
            (pin.layoutParams as FrameLayout.LayoutParams).marginEnd = SvUi.dp(context, 32)
            card.isHapticFeedbackEnabled = false
            card.isSoundEffectsEnabled = false
        }

        fun bind(e: ClipboardHistoryEntry) {
            entry = e
            val context = card.context
            val isSelected = e.id in selected
            val cardColor = SvUi.key().let { if (Color.alpha(it) < 0x40) ColorUtils.setAlphaComponent(SvUi.text(), 0x14) else it }
            val stroke = if (isSelected) SvUi.accent() else null
            card.background = SvUi.ripple(SvUi.surface(cardColor, radius).apply { if (stroke != null) setStroke(SvUi.dp(context, 2), stroke) }, radius)
            if (android.os.Build.VERSION.SDK_INT >= 23) card.foreground = if (isSelected && isImage) SvUi.rounded(ColorUtils.setAlphaComponent(SvUi.accent(), 0x40), radius, SvUi.accent(), SvUi.dp(context, 3)) else null
            pin.visibility = if (e.isPinned) View.VISIBLE else View.GONE
            check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            if (selectionMode) {
                val size = SvUi.dp(context, 22) / 2f
                if (isSelected) {
                    check.setImageDrawable(SvUi.icon(context, R.drawable.ic_sv_check, SvUi.onAccent()))
                    check.background = SvUi.rounded(SvUi.accent(), size)
                } else {
                    check.setImageDrawable(null)
                    check.background = SvUi.rounded(ColorUtils.setAlphaComponent(if (isImage) Color.BLACK else SvUi.text(), 0x30), size,
                        if (isImage) Color.WHITE else SvUi.secondaryText(), SvUi.dp(context, 2))
                }
            }
            val kind = SmartClipboard.kindOf(e)
            footer.text = if (isImage) {
                val bytes = java.io.File(helium314.keyboard.latin.database.ClipboardDao.clipFilesDir, e.filename!!).length()
                context.getString(R.string.sv_clip_photos) + " · " + android.text.format.Formatter.formatShortFileSize(context, bytes)
            } else if (kind == ClipKind.LINK) context.getString(R.string.sv_clip_links)
            else context.getString(R.string.sv_clip_text) + " · " + context.resources.getQuantityString(R.plurals.sv_clip_character_count, e.text.orEmpty().length, e.text.orEmpty().length)
            if (isImage) {
                val w = if (columnWidth > 0) columnWidth else SvUi.dp(context, 160)
                val lp = image.layoutParams
                val h = (w * 0.62f).toInt()
                if (lp.height != h) { lp.height = h; image.layoutParams = lp }
                image.contentDescription = e.text ?: context.getString(R.string.sv_clip_image)
                SmartClipboard.loadThumbnail(image, e.filename!!, w) { loaded ->
                    if (!loaded) {
                        image.setImageDrawable(SvUi.icon(context, R.drawable.ic_sv_close))
                        image.contentDescription = context.getString(R.string.sv_clip_missing)
                    }
                }
                return
            }
            if (textSizePx > 0) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            when (SmartClipboard.kindOf(e)) {
                ClipKind.LINK -> {
                    text.maxLines = 3
                    text.setTextColor(SvUi.accent())
                    val url = e.text?.trim().orEmpty()
                    val host = runCatching { android.net.Uri.parse(if ("://" in url) url else "https://$url").host }.getOrNull()
                    text.text = if (host.isNullOrBlank()) url else "$host\n$url"
                    text.setCompoundDrawablesRelativeWithIntrinsicBounds(SvUi.icon(context, R.drawable.ic_sv_link, SvUi.accent())?.apply {
                        val s = SvUi.dp(context, 16); setBounds(0, 0, s, s)
                    }, null, null, null)
                }
                ClipKind.FILE -> {
                    text.maxLines = 3
                    text.setTextColor(SvUi.text())
                    text.text = context.getString(R.string.sv_clip_file, e.mimeTypes?.firstOrNull() ?: "*/*") + (e.text?.let { "\n$it" } ?: "")
                    text.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                }
                else -> {
                    text.maxLines = if (e.id in expanded) Int.MAX_VALUE else 6
                    text.setTextColor(SvUi.text())
                    text.text = if (e.id in expanded) e.text else e.text?.take(600)
                    text.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                }
            }
            // keep the text clear of the pin / check badges
            val p = SvUi.dp(context, 12)
            text.setPadding(if (selectionMode) SvUi.dp(context, 32) else p, SvUi.dp(context, 34), p, SvUi.dp(context, 32))
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TEXT = 1
        const val TYPE_IMAGE = 2
    }
}
