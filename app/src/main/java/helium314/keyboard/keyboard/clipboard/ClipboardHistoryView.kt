// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.settings.Defaults
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.smart.clipboard.ClipCategory
import helium314.keyboard.smart.clipboard.SmartClipAdapter
import helium314.keyboard.smart.clipboard.SmartClipboard
import helium314.keyboard.smart.ui.SmartUi
import helium314.keyboard.smart.ui.SvUi

/**
 * Full clipboard: back arrow and categories in the top strip, pinned and recent clips in a two column grid
 * (large photo tiles, readable text cards), long press for selection with pin / reorder / delete.
 */
@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), ClipboardDao.Listener, SmartClipAdapter.Callbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val clipboardLayoutParams = ClipboardLayoutParams(context)

    private lateinit var recycler: RecyclerView
    private lateinit var placeholderView: TextView
    private val adapter = SmartClipAdapter(this).apply { setHasStableIds(true) }
    private var header: LinearLayout? = null

    lateinit var keyboardActionListener: KeyboardActionListener
    private var clipboardHistoryManager: ClipboardHistoryManager? = null
    private var category = ClipCategory.ALL
    private var pendingDelete: List<Long>? = null

    init {
        fitsSystemWindows = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        // The main keyboard expands to the entire this {@link KeyboardView}.
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    private fun initialize() {
        if (this::recycler.isInitialized) return
        placeholderView = findViewById(R.id.clipboard_empty_view)
        recycler = findViewById<RecyclerView>(R.id.clipboard_list).apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
            }
            itemAnimator = null
            clipboardLayoutParams.setListProperties(this)
            isVerticalScrollBarEnabled = false
        }
    }

    // ---------- top strip ----------

    private fun buildHeader() {
        val strip = KeyboardSwitcher.getInstance().clipboardStrip ?: return
        (strip.parent as? HorizontalScrollView)?.isFillViewport = true
        strip.removeAllViews()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        strip.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f })
        header = row
        updateHeader()
    }

    private fun stripButton(icon: Int, description: Int, enabled: Boolean = true, onClick: () -> Unit): ImageButton =
        SvUi.iconButton(context, icon, context.getString(description), 44) { onClick() }.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
        }

    private fun updateHeader() {
        val row = header ?: return
        row.removeAllViews()
        pendingDelete?.let { ids ->
            row.addView(SvUi.label(context, 14f).apply {
                text = context.getString(R.string.sv_clip_confirm_delete, ids.size)
            }, LayoutParams(0, SvUi.dp(context, 44), 1f))
            row.addView(stripButton(R.drawable.ic_sv_close, android.R.string.cancel) { pendingDelete = null; updateHeader() })
            row.addView(stripButton(R.drawable.ic_sv_check, R.string.sv_clip_delete) {
                pendingDelete = null
                clipboardHistoryManager?.deleteIds(ids)
                setSelectionMode(false)
            })
            return
        }
        val btn = LayoutParams(SvUi.dp(context, 44), SvUi.dp(context, 44))
        if (adapter.selectionMode) {
            row.addView(stripButton(R.drawable.ic_sv_close, R.string.sv_clip_cancel_selection) { setSelectionMode(false) }, btn)
            row.addView(SvUi.label(context, 15f, bold = true).apply {
                text = context.getString(R.string.sv_clip_selected, adapter.selected.size)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(SvUi.dp(context, 4), 0, 0, 0)
            }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val one = adapter.selected.size == 1
            val any = adapter.selected.isNotEmpty()
            row.addView(stripButton(R.drawable.ic_select_all_rounded, R.string.sv_clip_select_all) { selectAll() }, btn)
            val allPinned = any && selectedEntries().all { it.isPinned }
            row.addView(stripButton(R.drawable.ic_sv_pin, if (allPinned) R.string.sv_clip_unpin else R.string.sv_clip_pin, any) {
                clipboardHistoryManager?.setPinned(adapter.selected, !allPinned)
            }, btn)
            row.addView(stripButton(R.drawable.ic_sv_arrow_up, R.string.sv_clip_move_up, one) { moveSelected(true) }, btn)
            row.addView(stripButton(R.drawable.ic_sv_arrow_up, R.string.sv_clip_move_down, one) { moveSelected(false) }.apply { rotation = 180f }, btn)
            row.addView(stripButton(R.drawable.ic_sv_delete, R.string.sv_clip_delete, any) { deleteSelected() }, btn)
            return
        }
        row.addView(stripButton(R.drawable.ic_arrow_back, R.string.sv_back) { backToKeyboard() }, btn)
        row.addView(SvUi.label(context, 16f, bold = true).apply {
            setText(R.string.sv_fn_clipboard)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(android.widget.Switch(context).apply {
            contentDescription = context.getString(R.string.sv_clip_history_toggle)
            thumbTintList = android.content.res.ColorStateList.valueOf(SvUi.accent())
            trackTintList = android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.blendARGB(SvUi.key(), SvUi.accent(), 0.35f))
            isChecked = context.prefs().getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
            setOnCheckedChangeListener { _, checked ->
                context.prefs().edit().putBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, checked).apply()
            }
        }, LayoutParams(SvUi.dp(context, 48), SvUi.dp(context, 44)))
        row.addView(stripButton(R.drawable.ic_edit, R.string.sv_clip_select) { setSelectionMode(true) }, btn)
    }

    private fun buildCategories() {
        val chips = findViewById<LinearLayout>(R.id.sv_clip_categories)
        chips.removeAllViews()
        listOf(ClipCategory.ALL to R.string.sv_clip_all, ClipCategory.TEXT to R.string.sv_clip_text,
            ClipCategory.IMAGES to R.string.sv_clip_photos, ClipCategory.LINKS to R.string.sv_clip_links,
            ClipCategory.PINNED to R.string.sv_clip_pinned).forEach { (cat, label) ->
            val chip = SvUi.chip(context, context.getString(label), filled = cat == category).apply {
                val icon = when (cat) {
                    ClipCategory.ALL -> R.drawable.ic_sv_apps
                    ClipCategory.TEXT -> R.drawable.ic_sv_text
                    ClipCategory.IMAGES -> R.drawable.ic_sv_image
                    ClipCategory.LINKS -> R.drawable.ic_sv_link
                    ClipCategory.PINNED -> R.drawable.ic_sv_pin
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(SvUi.icon(context, icon, if (cat == category) android.graphics.Color.WHITE else SvUi.text()), null, null, null)
                compoundDrawablePadding = SvUi.dp(context, 4)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(SvUi.dp(context, 11), SvUi.dp(context, 6), SvUi.dp(context, 11), SvUi.dp(context, 6))
                setOnClickListener { SvUi.haptic(it); category = cat; buildCategories(); refresh(scrollTop = true) }
            }
            chips.addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = SvUi.dp(context, 5) })
        }
        val search = findViewById<LinearLayout>(R.id.sv_clip_search_row)
        search.removeAllViews()
        search.addView(SvUi.chip(context, context.getString(R.string.sv_clip_search)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(SvUi.secondaryText())
            setCompoundDrawablesRelativeWithIntrinsicBounds(SvUi.icon(context, R.drawable.sym_keyboard_search_lxx), null, null, null)
            compoundDrawablePadding = SvUi.dp(context, 8)
            setOnClickListener {
                SmartUi.openClipboardSearch()
            }
        }, LayoutParams(0, SvUi.dp(context, 36), 1f))
        search.addView(stripButton(R.drawable.ic_sv_delete, R.string.sv_clip_clear_recent) {
            pendingDelete = entries().filter { !it.isPinned }.map { it.id }; updateHeader()
        }, LayoutParams(SvUi.dp(context, 44), SvUi.dp(context, 40)))
    }

    // ---------- list ----------

    private fun entries(): List<ClipboardHistoryEntry> = clipboardHistoryManager?.getAllEntries().orEmpty()

    private fun selectedEntries() = entries().filter { it.id in adapter.selected }

    private fun refresh(scrollTop: Boolean = false) {
        val all = entries().filter { SmartClipboard.matches(it, category) }
        val rows = ArrayList<SmartClipAdapter.Row>(all.size + 4)
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val yesterday = java.util.Calendar.getInstance().apply { timeInMillis = today; add(java.util.Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        val groups = all.sortedWith(compareByDescending<ClipboardHistoryEntry> { it.isPinned }.thenByDescending { it.timeStamp })
            .groupBy { when {
                it.isPinned -> context.getString(R.string.sv_clip_pinned)
                it.timeStamp >= today -> context.getString(R.string.sv_clip_today)
                it.timeStamp >= yesterday -> context.getString(R.string.sv_clip_yesterday)
                else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it.timeStamp))
            } }
        groups.forEach { (label, clips) ->
            rows.add(SmartClipAdapter.Row.Header(R.string.sv_clip_recent, label))
            clips.forEach { rows.add(SmartClipAdapter.Row.Clip(it)) }
        }
        adapter.selected.retainAll(all.mapTo(HashSet()) { it.id })
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        val empty = rows.isEmpty()
        placeholderView.visibility = if (empty) VISIBLE else INVISIBLE
        recycler.visibility = if (empty) INVISIBLE else VISIBLE
        placeholderView.setText(when (category) {
            ClipCategory.ALL -> R.string.sv_clip_empty
            ClipCategory.TEXT -> R.string.sv_clip_empty_text
            ClipCategory.IMAGES -> R.string.sv_clip_empty_photos
            ClipCategory.LINKS -> R.string.sv_clip_empty_links
            ClipCategory.PINNED -> R.string.sv_clip_empty_pinned
        })
        if (scrollTop) recycler.scrollToPosition(0)
        if (adapter.selectionMode) updateHeader()
    }

    private fun setSelectionMode(on: Boolean) {
        adapter.selectionMode = on
        if (!on) adapter.selected.clear()
        updateHeader()
        adapter.notifyDataSetChanged()
    }

    private fun selectAll() {
        val ids = adapter.rows.mapNotNull { (it as? SmartClipAdapter.Row.Clip)?.entry?.id }
        if (adapter.selected.containsAll(ids)) adapter.selected.clear() else adapter.selected.addAll(ids)
        updateHeader()
        adapter.notifyDataSetChanged()
    }

    private fun moveSelected(up: Boolean) {
        val id = adapter.selected.singleOrNull() ?: return
        clipboardHistoryManager?.moveInGroup(id, up)
    }

    private fun deleteSelected() {
        pendingDelete = adapter.selected.toList()
        updateHeader()
    }

    private fun backToKeyboard() {
        keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    /** System back: leave selection first, then return to the letters. */
    fun onBack(): Boolean {
        if (pendingDelete != null) { pendingDelete = null; updateHeader(); return true }
        if (adapter.selectionMode) { setSelectionMode(false); return true }
        backToKeyboard()
        return true
    }

    override fun onClipClick(entry: ClipboardHistoryEntry) {
        if (adapter.selectionMode) {
            if (!adapter.selected.remove(entry.id)) adapter.selected.add(entry.id)
            updateHeader()
            adapter.notifyDataSetChanged()
            return
        }
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, 1, HapticEvent.KEY_PRESS)
        if (entry.filename != null) {
            if (!java.io.File(ClipboardDao.clipFilesDir, entry.filename).isFile) {
                android.widget.Toast.makeText(context, R.string.sv_clip_missing, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            runCatching { keyboardActionListener.onContent(entry.getContentInfo(context)) }.onFailure {
                android.widget.Toast.makeText(context, R.string.sv_clip_missing, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        else keyboardActionListener.onTextInput(entry.text)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun onClipLongClick(entry: ClipboardHistoryEntry) {
        SvUi.haptic(this)
        if (!adapter.selectionMode) adapter.selectionMode = true
        adapter.selected.add(entry.id)
        updateHeader()
        adapter.notifyDataSetChanged()
    }

    override fun onClipMenu(entry: ClipboardHistoryEntry, anchor: View) {
        val menu = LinearLayout(context).apply { orientation = VERTICAL; setPadding(8, 8, 8, 8) }
        val scroll = android.widget.ScrollView(context).apply { addView(menu) }
        val popupHeight = minOf(SvUi.dp(context, 240), height - SvUi.dp(context, 12)).coerceAtLeast(SvUi.dp(context, 48))
        val popup = android.widget.PopupWindow(scroll, SvUi.dp(context, 220), popupHeight, true).apply {
            setBackgroundDrawable(SvUi.surface(SvUi.panelBackground(), SvUi.dp(context, 18).toFloat()))
            isOutsideTouchable = true
            elevation = SvUi.dp(context, 6).toFloat()
        }
        fun option(label: Int, action: () -> Unit) {
            menu.addView(SvUi.label(context, 15f).apply {
                setText(label); minHeight = SvUi.dp(context, 48)
                setOnClickListener { popup.dismiss(); action() }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        option(R.string.sv_fn_paste) { onClipClick(entry) }
        if (entry.filename != null) option(R.string.sv_clip_share_title) {
            if (java.io.File(ClipboardDao.clipFilesDir, entry.filename).isFile) {
                val content = entry.getContentInfo(context)
                SmartClipboard.shareFallback(context, content.contentUri, content.description)
            } else android.widget.Toast.makeText(context, R.string.sv_clip_missing, android.widget.Toast.LENGTH_LONG).show()
        }
        option(if (entry.isPinned) R.string.sv_clip_unpin else R.string.sv_clip_pin) {
            clipboardHistoryManager?.setPinned(listOf(entry.id), !entry.isPinned)
        }
        if (entry.filename == null) option(R.string.sv_clip_expand) {
            if (!adapter.expanded.add(entry.id)) adapter.expanded.remove(entry.id)
            adapter.notifyDataSetChanged()
        }
        option(R.string.sv_clip_select) { onClipLongClick(entry) }
        option(R.string.sv_clip_delete) { pendingDelete = listOf(entry.id); updateHeader() }
        // A card near the IME's lower edge has no room for a dropdown. Anchor the
        // scrollable actions above the system navigation region instead.
        val nav = rootWindowInsets?.stableInsetBottom ?: 0
        popup.showAtLocation(this, Gravity.BOTTOM or Gravity.END, SvUi.dp(context, 8), nav + SvUi.dp(context, 8))
    }

    // ---------- lifecycle ----------

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
        keyboardView.visibility = GONE
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        clipboardHistoryManager = historyManager
        initialize()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        adapter.selectionMode = false
        adapter.selected.clear()
        pendingDelete = null
        category = ClipCategory.ALL
        SmartUi.clipboardBack = { onBack() }

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        setupBottomRowKeyboard(editorInfo, keyboardActionListener)

        placeholderView.apply {
            KeyboardTypeface.applyToTextView(this)
            setTextColor(SvUi.secondaryText())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(SvUi.dp(context, 32), 0, SvUi.dp(context, 32), 0)
        }
        val settings = Settings.getInstance()
        recycler.apply {
            adapter = this@ClipboardHistoryView.adapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth
            ClipboardLayoutParams(context).setListProperties(this)
            val keyboardAttr = context.obtainStyledAttributes(null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
            val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * settings.current.mSidePaddingScale).toInt()
            val rightPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * settings.current.mSidePaddingScale).toInt()
            keyboardAttr.recycle()
            val pad = SvUi.dp(context, 4)
            setPadding(leftPadding + pad, pad, rightPadding + pad, pad)
            clipToPadding = false
            this@ClipboardHistoryView.adapter.columnWidth = (keyboardWidth - leftPadding - rightPadding - 6 * pad) / 2
        }
        adapter.textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        setBackgroundColor(SvUi.panelBackground())
        buildHeader()
        buildCategories()
        refresh(scrollTop = true)
    }

    fun stopClipboardHistory() {
        if (!this::recycler.isInitialized) return
        recycler.adapter = null
        clipboardHistoryManager?.setHistoryChangeListener(null)
        if (SmartUi.clipboardBack != null) SmartUi.clipboardBack = null
        SmartClipboard.clearThumbnails()
    }

    override fun onClipInserted(position: Int) = refresh(scrollTop = true)

    override fun onClipsRemoved(position: Int, count: Int) = refresh()

    override fun onClipMoved(oldPosition: Int, newPosition: Int) = refresh()

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (clipboardHistoryManager != null && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            clipboardHistoryManager?.sortHistoryEntries()
            if (this::recycler.isInitialized) refresh()
        }
    }
}
