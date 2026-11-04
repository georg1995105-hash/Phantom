// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.smart.SmartPrefs

/** The panel behind the 4-dots button: large function icons, plus quick theme and language pages. */
class FunctionsPanelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var ime: LatinIME? = null

    private enum class Page { MAIN, THEME, LANGUAGES }
    private var page = Page.MAIN
    private var editing = false

    private val header = LinearLayout(context)
    private val title = SvUi.label(context, 16f, bold = true)
    private val content = FrameLayout(context)

    init {
        isClickable = true
        visibility = GONE
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        title.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, SvUi.dp(context, 44)))
        column.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // Must never make its container taller than the keyboard: the real size comes from an EXACTLY pass.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun show() {
        editing = false
        showPage(Page.MAIN)
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }

    fun onBack() {
        when {
            page != Page.MAIN -> showPage(Page.MAIN)
            editing -> { editing = false; showPage(Page.MAIN) }
            else -> hide()
        }
    }

    private fun buildHeader() {
        header.removeAllViews()
        val back = SvUi.iconButton(context, R.drawable.ic_arrow_back, context.getString(R.string.sv_back)) { onBack() }
        header.addView(back, LinearLayout.LayoutParams(SvUi.dp(context, 52), LayoutParams.MATCH_PARENT))
        title.setTextColor(SvUi.text())
        header.addView(title, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        if (page == Page.MAIN) {
            val edit = TextView(context).apply {
                text = context.getString(if (editing) R.string.sv_functions_done else R.string.sv_functions_edit)
                setTextColor(SvUi.accent())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(SvUi.dp(context, 8), 0, SvUi.dp(context, 12), 0)
                setOnClickListener { editing = !editing; showPage(Page.MAIN) }
            }
            header.addView(edit, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun showPage(p: Page) {
        page = p
        setBackgroundColor(SvUi.panelBackground())
        buildHeader()
        content.removeAllViews()
        when (p) {
            Page.MAIN -> { title.text = context.getString(R.string.sv_functions_title); content.addView(mainGrid()) }
            Page.THEME -> { title.text = context.getString(R.string.sv_fn_theme); content.addView(themePage()) }
            Page.LANGUAGES -> { title.text = context.getString(R.string.sv_fn_languages); content.addView(languagesPage()) }
        }
    }

    private class Item(val id: String, val icon: Int, val label: Int, val action: () -> Unit)

    private fun code(code: Int) {
        hide()
        ime?.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    private fun items() = listOf(
        Item("clipboard", R.drawable.ic_sv_clipboard, R.string.sv_fn_clipboard) { code(KeyCode.CLIPBOARD) },
        Item("settings", R.drawable.ic_sv_settings, R.string.sv_fn_settings) { hide(); ime?.svLaunchSettings(null) },
        Item("theme", R.drawable.ic_sv_palette, R.string.sv_fn_theme) { showPage(Page.THEME) },
        Item("resize", R.drawable.ic_sv_resize, R.string.sv_fn_resize) { SmartUi.startResize() },
        Item("languages", R.drawable.ic_sv_language, R.string.sv_fn_languages) { showPage(Page.LANGUAGES) },
        Item("emoji", R.drawable.ic_sv_emoji, R.string.sv_fn_emoji) { code(KeyCode.EMOJI) },
        Item("numpad", R.drawable.sym_keyboard_numpad_key_lxx, R.string.sv_fn_numpad) { code(KeyCode.NUMPAD) },
    )

    private fun orderedItems(): List<Item> {
        val ids = context.prefs().getString(SmartPrefs.FUNCTIONS_ORDER, null)
            ?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        return items().sortedBy { ids.indexOf(it.id).let { pos -> if (pos < 0) Int.MAX_VALUE else pos } }
    }

    private fun hiddenItems(): Set<String> =
        context.prefs().getStringSet(SmartPrefs.FUNCTIONS_HIDDEN, emptySet()).orEmpty()

    private fun toggleHidden(item: Item) {
        if (item.id == "settings") return
        val hidden = hiddenItems().toMutableSet()
        if (!hidden.add(item.id)) hidden.remove(item.id)
        context.prefs().edit().putStringSet(SmartPrefs.FUNCTIONS_HIDDEN, hidden).apply()
        showPage(Page.MAIN)
    }

    private fun moveItem(from: String, to: String) {
        if (from == to) return
        val ids = orderedItems().map { it.id }.toMutableList()
        if (!ids.remove(from) || to !in ids) return
        ids.add(ids.indexOf(to), from)
        context.prefs().edit().putString(SmartPrefs.FUNCTIONS_ORDER, ids.joinToString("|")).apply()
        showPage(Page.MAIN)
    }

    private fun mainGrid(): View {
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SvUi.dp(context, 8), 0, SvUi.dp(context, 8), SvUi.dp(context, 8))
        }
        val hidden = hiddenItems()
        orderedItems().filter { editing || it.id !in hidden }.chunked(4).forEach { rowItems ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { item ->
                row.addView(itemView(item, item.id in hidden),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(4 - rowItems.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
        scroll.addView(grid)
        return scroll
    }

    private fun itemView(item: Item, hidden: Boolean): View {
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(SvUi.dp(context, 2), SvUi.dp(context, 4), SvUi.dp(context, 2), SvUi.dp(context, 4))
            background = SvUi.ripple(null, SvUi.dp(context, 16).toFloat())
            contentDescription = context.getString(item.label)
            setOnClickListener {
                SvUi.haptic(it)
                if (editing) toggleHidden(item) else item.action()
            }
        }
        val color = SvUi.key().let {
            if (android.graphics.Color.alpha(it) < 0x40)
                ColorUtils.setAlphaComponent(SvUi.text(), 0x18) else it
        }
        val icon = ImageView(context).apply {
            setImageDrawable(SvUi.icon(context, item.icon, SvUi.text()))
            scaleType = ImageView.ScaleType.CENTER
            alpha = if (hidden) 0.35f else 1f
            background = SvUi.rounded(color, SvUi.dp(context, 18).toFloat())
        }
        v.addView(icon, LinearLayout.LayoutParams(SvUi.dp(context, 50), SvUi.dp(context, 50)))
        val label = SvUi.label(context, 12f).apply {
            text = if (editing) {
                val mark = if (hidden) "+" else if (item.id == "settings") "·" else "−"
                mark + " " + context.getString(item.label)
            } else context.getString(item.label)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, SvUi.dp(context, 4), 0, 0)
        }
        v.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        v.setOnLongClickListener {
            editing = true
            val data = ClipData.newPlainText("smartvoice-menu-item", item.id)
            val shadow = View.DragShadowBuilder(v)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                v.startDragAndDrop(data, shadow, item.id, 0)
            } else {
                @Suppress("DEPRECATION")
                v.startDrag(data, shadow, item.id, 0)
            }
            true
        }
        v.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                DragEvent.ACTION_DROP -> {
                    val source = event.localState as? String ?: return@setOnDragListener false
                    moveItem(source, item.id)
                    true
                }
                else -> true
            }
        }
        return v
    }

    // ---------- theme page ----------

    data class ThemeChoice(val id: String, val label: Int, val dayNight: Boolean, val day: String, val night: String)

    private fun themePage(): View {
        val prefs = context.prefs()
        val dayNight = prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
        val day = prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
        val current = when {
            dayNight -> "auto"
            else -> day
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(SvUi.dp(context, 10), SvUi.dp(context, 4), SvUi.dp(context, 10), SvUi.dp(context, 4))
        }
        THEMES.forEach { t ->
            val selected = t.id == current
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(SvUi.dp(context, 4), SvUi.dp(context, 4), SvUi.dp(context, 4), SvUi.dp(context, 4))
                background = SvUi.ripple(null, SvUi.dp(context, 14).toFloat())
                setOnClickListener { SvUi.haptic(it); applyTheme(t) }
            }
            card.addView(ThemePreview(context, t, selected), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SvUi.dp(context, 84)))
            card.addView(SvUi.label(context, 13f).apply {
                text = context.getString(t.label)
                setPadding(0, SvUi.dp(context, 6), 0, 0)
                if (selected) paint.isFakeBoldText = true
            })
            row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(row)
        val more = SvUi.chip(context, context.getString(R.string.sv_theme_more)).apply {
            setOnClickListener { SvUi.haptic(it); hide(); ime?.svLaunchSettings("theme") }
        }
        column.addView(more, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = SvUi.dp(context, 8)
        })
        return ScrollView(context).apply { addView(column) }
    }

    private fun applyTheme(t: ThemeChoice) {
        val prefs = context.prefs()
        prefs.edit()
            .putBoolean(Settings.PREF_THEME_DAY_NIGHT, t.dayNight)
            .putString(Settings.PREF_THEME_COLORS, t.day)
            .putString(Settings.PREF_THEME_COLORS_NIGHT, t.night)
            .apply()
        hide()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    /** Miniature keyboard drawn with the theme colors. */
    class ThemePreview(context: Context, private val theme: ThemeChoice, private val selected: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = SvUi.dp(context, 12).toFloat()
            if (theme.dayNight) {
                drawHalf(canvas, colorsOf(theme.day), 0f, w / 2, w, h, r, left = true)
                drawHalf(canvas, colorsOf(theme.night), w / 2, w, w, h, r, left = false)
            } else drawHalf(canvas, colorsOf(theme.day), 0f, w, w, h, r, left = null)
            if (selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = SvUi.dp(context, 2.5f).toFloat()
                paint.color = SvUi.accent()
                val i = paint.strokeWidth / 2
                rect.set(i, i, w - i, h - i)
                canvas.drawRoundRect(rect, r, r, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun drawHalf(canvas: Canvas, c: IntArray, x0: Float, x1: Float, w: Float, h: Float, r: Float, left: Boolean?) {
            canvas.save()
            canvas.clipRect(x0, 0f, x1, h)
            paint.style = Paint.Style.FILL
            paint.color = c[0]
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, paint)
            val pad = w * 0.06f
            val rows = 4
            val gap = h * 0.05f
            val rowH = (h - 2 * pad - (rows - 1) * gap) / rows
            for (row in 0 until rows) {
                val keys = if (row == rows - 1) 3 else 6
                val y = pad + row * (rowH + gap)
                val keyW = (w - 2 * pad - (keys - 1) * gap) / keys
                for (k in 0 until keys) {
                    val x = pad + k * (keyW + gap)
                    paint.color = when {
                        row == rows - 1 && k == 1 -> c[1]
                        row == rows - 1 && k == 2 -> c[3]
                        row == rows - 1 -> c[2]
                        else -> c[1]
                    }
                    rect.set(x, y, x + keyW, y + rowH)
                    canvas.drawRoundRect(rect, rowH * 0.25f, rowH * 0.25f, paint)
                }
            }
            canvas.restore()
        }

        private fun colorsOf(name: String): IntArray = themeColors(name)
    }

    // ---------- languages page ----------

    private fun languagesPage(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SvUi.dp(context, 12), SvUi.dp(context, 4), SvUi.dp(context, 12), SvUi.dp(context, 8))
        }
        val current = ime?.svCurrentSubtype()
        SubtypeSettings.getEnabledSubtypes(true).forEach { subtype ->
            val selected = subtype == current
            val row = TextView(context).apply {
                text = subtype.displayName()
                setTextColor(if (selected) SvUi.onAccent() else SvUi.text())
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(SvUi.dp(context, 16), 0, SvUi.dp(context, 16), 0)
                val r = SvUi.dp(context, 14).toFloat()
                background = SvUi.ripple(SvUi.rounded(if (selected) SvUi.accent() else ColorUtils.setAlphaComponent(SvUi.text(), 0x12), r), r)
                setOnClickListener { SvUi.haptic(it); hide(); ime?.switchToSubtype(subtype) }
            }
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SvUi.dp(context, 48)).apply { bottomMargin = SvUi.dp(context, 6) })
        }
        val more = SvUi.chip(context, context.getString(R.string.sv_languages_manage)).apply {
            setOnClickListener { SvUi.haptic(it); hide(); ime?.svLaunchSettings("languages") }
        }
        column.addView(more, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = SvUi.dp(context, 4)
        })
        return ScrollView(context).apply { addView(column) }
    }

    companion object {
        /** background, key, functional key, accent */
        fun themeColors(name: String): IntArray = when (name) {
            KeyboardTheme.THEME_SV_DARK -> intArrayOf("#202124".toColorInt(), "#3C4043".toColorInt(), "#2D2F31".toColorInt(), "#A8C7FA".toColorInt())
            KeyboardTheme.THEME_SV_GRAPHITE -> intArrayOf("#141517".toColorInt(), "#2C2D30".toColorInt(), "#3D3F43".toColorInt(), "#A8C7FA".toColorInt())
            else -> intArrayOf("#EDEFF2".toColorInt(), "#FFFFFF".toColorInt(), "#D5D9DF".toColorInt(), "#0B57D0".toColorInt())
        }

        val THEMES = listOf(
            ThemeChoice("auto", R.string.sv_theme_auto, true, KeyboardTheme.THEME_SV_LIGHT, KeyboardTheme.THEME_SV_DARK),
            ThemeChoice(KeyboardTheme.THEME_SV_LIGHT, R.string.sv_theme_light, false, KeyboardTheme.THEME_SV_LIGHT, KeyboardTheme.THEME_SV_DARK),
            ThemeChoice(KeyboardTheme.THEME_SV_DARK, R.string.sv_theme_dark, false, KeyboardTheme.THEME_SV_DARK, KeyboardTheme.THEME_SV_DARK),
            ThemeChoice(KeyboardTheme.THEME_SV_GRAPHITE, R.string.sv_theme_graphite, false, KeyboardTheme.THEME_SV_GRAPHITE, KeyboardTheme.THEME_SV_GRAPHITE),
        )
    }
}
