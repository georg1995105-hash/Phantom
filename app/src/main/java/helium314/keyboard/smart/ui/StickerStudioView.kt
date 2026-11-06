// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.smart.clipboard.SmartClipboard
import helium314.keyboard.smart.emoji.GifImportActivity
import helium314.keyboard.smart.emoji.StickerStore

/** Local emoji compositor + persistent sticker/GIF library in the current IME window. */
class StickerStudioView(private val ime: LatinIME, library: Boolean, private val gifs: Boolean = false) : LinearLayout(ime) {
    private var base = "😎"
    private var badge = "✨"
    private var editingBadge = false
    private var saving = false
    private val content = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(SvUi.panelBackground())
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(SvUi.iconButton(context, R.drawable.ic_sv_back, context.getString(R.string.sv_back)) { SmartUi.closeInlinePanel() })
        header.addView(SvUi.label(context, 17f, bold = true).apply {
            setText(if (gifs) R.string.sv_gif_library else R.string.sv_sticker_studio)
        }, LayoutParams(0, SvUi.dp(context, 48), 1f))
        header.addView(SvUi.chip(context, if (gifs) "+ GIF" else context.getString(R.string.sv_sticker_library)).apply {
            tag = "sv_test_sticker_library"
            setOnClickListener { if (gifs) GifImportActivity.open(ime) else showLibrary() }
        })
        addView(header)
        addView(content, LayoutParams(-1, 0, 1f))
        if (library) showLibrary() else showComposer()
    }

    private fun showComposer() {
        content.removeAllViews()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val column = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val preview = FrameLayout(context).apply { background = SvUi.surface(radius = SvUi.dp(context, 20).toFloat()) }
        preview.addView(SvUi.label(context, 78f).apply { text = base }, FrameLayout.LayoutParams(-1, -1))
        preview.addView(SvUi.label(context, 38f).apply { text = badge }, FrameLayout.LayoutParams(SvUi.dp(context, 58), SvUi.dp(context, 58), Gravity.END or Gravity.BOTTOM))
        column.addView(preview, LayoutParams(SvUi.dp(context, 152), SvUi.dp(context, 126)))
        val selectors = LinearLayout(context)
        listOf(false, true).forEach { badgeSlot ->
            selectors.addView(SvUi.chip(context, context.getString(if (badgeSlot) R.string.sv_sticker_badge else R.string.sv_sticker_base), editingBadge == badgeSlot).apply {
                setOnClickListener { editingBadge = badgeSlot; showComposer() }
            }, LayoutParams(0, SvUi.dp(context, 44), 1f).apply { setMargins(4, 4, 4, 4) })
        }
        column.addView(selectors, LayoutParams(-1, -2))
        val choices = GridLayout(context).apply { columnCount = 8 }
        val values = if (editingBadge) listOf("✨", "❤️", "🔥", "💯", "🎉", "👍", "💻", "") else
            listOf("😎", "😺", "🥰", "😂", "😀", "🤔", "😭", "😡", "🐱", "🐶", "🦊", "🐼", "👻", "🤖", "🍀", "☕")
        values.forEach { emoji ->
            choices.addView(SvUi.label(context, 28f).apply {
                tag = "sv_test_emoji_" + emoji.codePoints().toArray().joinToString("_")
                text = emoji.ifEmpty { "×" }
                contentDescription = emoji.ifEmpty { context.getString(R.string.sv_sticker_no_badge) }
                background = SvUi.ripple(null, SvUi.dp(context, 12).toFloat())
                setOnClickListener { if (editingBadge) badge = emoji else base = emoji; showComposer() }
            }, GridLayout.LayoutParams().apply { width = 0; height = SvUi.dp(context, 46); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
        }
        column.addView(choices, LayoutParams(-1, -2))
        column.addView(SvUi.label(context, 12f, SvUi.secondaryText()).apply {
            setText(R.string.sv_sticker_local_hint); setPadding(8, 6, 8, 6)
        })
        scroll.addView(column)
        content.addView(scroll, LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context)
        for (insert in listOf(false, true)) actions.addView(SvUi.chip(context,
            context.getString(if (insert) R.string.sv_sticker_save_insert else R.string.sv_sticker_save), filled = insert).apply {
            tag = if (insert) "sv_test_sticker_insert" else "sv_test_sticker_save"
            setOnClickListener { save(insert) }
        }, LayoutParams(0, SvUi.dp(context, 48), 1f).apply { setMargins(6, 4, 6, 4) })
        content.addView(actions)
    }

    private fun save(insert: Boolean) {
        if (saving) return
        saving = true
        val chosenBase = base
        val chosenBadge = badge
        StickerStore.worker.execute {
            val result = runCatching { StickerStore.create(context, chosenBase, chosenBadge) }
            post {
                saving = false
                if (!isAttachedToWindow) return@post
                result.onSuccess {
                    Toast.makeText(context, R.string.sv_sticker_saved, Toast.LENGTH_SHORT).show()
                    if (insert) ime.mKeyboardActionListener.onContent(it.content(context)) else showLibrary()
                }.onFailure { Toast.makeText(context, R.string.sv_media_error, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showLibrary() {
        content.removeAllViews()
        if (!gifs) content.addView(SvUi.chip(context, context.getString(R.string.sv_sticker_create)).apply {
            setOnClickListener { showComposer() }
        }, LayoutParams(-1, SvUi.dp(context, 44)))
        val recycler = RecyclerView(context).apply { layoutManager = GridLayoutManager(context, 3); itemAnimator = null }
        content.addView(recycler, LayoutParams(-1, 0, 1f))
        StickerStore.worker.execute {
            val items = StickerStore.list(context, gifs)
            post {
                if (!isAttachedToWindow || recycler.parent !== content) return@post
                if (items.isEmpty()) {
                    val position = content.indexOfChild(recycler)
                    content.removeView(recycler)
                    content.addView(SvUi.label(context, 14f, SvUi.secondaryText()).apply {
                        setText(if (gifs) R.string.sv_gif_empty else R.string.sv_sticker_empty)
                        setPadding(20, 12, 20, 12)
                    }, position, LayoutParams(-1, 0, 1f))
                } else recycler.adapter = LibraryAdapter(items)
            }
        }
        content.addView(SvUi.label(context, 12f, SvUi.secondaryText()).apply { setText(R.string.sv_sticker_delete_hint) })
    }

    private inner class LibraryAdapter(private val items: List<StickerStore.Item>) : RecyclerView.Adapter<LibraryAdapter.Holder>() {
        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, SvUi.dp(context, 108)); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(8, 8, 8, 8); background = SvUi.ripple(SvUi.surface(radius = 14f), 14f)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.image.tag = item.file.name
            holder.image.setImageDrawable(null)
            holder.image.contentDescription = context.getString(if (item.isGif) R.string.sv_gif_insert else R.string.sv_sticker_insert)
            StickerStore.worker.execute {
                val bmp = SmartClipboard.decodeFile(item.file, SvUi.dp(context, 108))
                holder.image.post { if (holder.image.tag == item.file.name) holder.image.setImageBitmap(bmp) }
            }
            holder.image.setOnClickListener { ime.mKeyboardActionListener.onContent(item.content(context)) }
            holder.image.setOnLongClickListener {
                // A visible confirmation in the same IME, never silently delete a saved item.
                holder.image.setImageDrawable(SvUi.icon(context, R.drawable.ic_sv_close))
                holder.image.contentDescription = context.getString(R.string.sv_sticker_confirm_delete)
                holder.image.setOnClickListener {
                    StickerStore.worker.execute { item.file.delete(); post { if (isAttachedToWindow) showLibrary() } }
                }
                true
            }
        }
    }
}
