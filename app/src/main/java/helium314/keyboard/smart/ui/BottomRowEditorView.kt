// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.smart.BottomRowOrder
import helium314.keyboard.smart.BottomNumpad
import helium314.keyboard.smart.SmartPrefs

/**
 * Compact lower-row editor. Open by long-pressing ?123, emoji or numeric shortcut.
 * Keep all letter keys visible. Tap to pin/unpin; drag a chip to reorder the row.
 */
class BottomRowEditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    private data class Shortcut(val id: String, val glyph: String, val pref: String, val defaultValue: Boolean)

    private val shortcuts = listOf(
        Shortcut("emoji", "☺", Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY),
        Shortcut("language_switch", "🌐", Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY),
        Shortcut("numpad", "123", SmartPrefs.BOTTOM_NUMPAD, false)
    )
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(SvUi.dp(context, 4), 0, SvUi.dp(context, 4), 0)
    }

    init {
        visibility = GONE
        isClickable = true
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun show() {
        visibility = VISIBLE
        render()
    }

    fun hide() {
        visibility = GONE
    }

    private fun switch(item: Shortcut) {
        if (item.id == "numpad") {
            showNumpadChoices(context) { SmartUi.refreshBottomRow(); render() }
            return
        }
        val prefs = context.prefs()
        val active = prefs.getBoolean(item.pref, item.defaultValue)
        prefs.edit().putBoolean(item.pref, !active).apply()
        SmartUi.refreshBottomRow()
        render()
    }

    private fun move(from: String, before: String) {
        if (from == before) return
        val prefs = context.prefs()
        val previous = prefs.getString(SmartPrefs.BOTTOM_ORDER, null)
        prefs.edit().putString(SmartPrefs.BOTTOM_ORDER,
            BottomRowOrder.move(previous, from, before)).apply()
        SmartUi.refreshBottomRow()
        render()
    }

    private fun chip(glyph: String, description: String, selected: Boolean = false,
                     flex: Float = 0f): TextView {
        return TextView(context).apply {
            text = glyph
            contentDescription = description
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (selected) SvUi.accent() else SvUi.text())
            setPadding(SvUi.dp(context, 5), 0, SvUi.dp(context, 5), 0)
            background = SvUi.ripple(null, SvUi.dp(context, 12).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                if (flex > 0f) 0 else SvUi.dp(context, 54),
                LayoutParams.MATCH_PARENT,
                flex
            ).apply { marginEnd = SvUi.dp(context, 2) }
        }
    }

    private fun render() {
        bar.removeAllViews()
        bar.setBackgroundColor(SvUi.panelBackground())
        bar.addView(chip(context.getString(R.string.sv_bottom_edit_label),
            context.getString(R.string.sv_bottom_edit_label), flex = 1f).apply {
            background = null
        })
        val prefs = context.prefs()
        val savedOrder = prefs.getString(SmartPrefs.BOTTOM_ORDER, null)
        val ordered = BottomRowOrder.sort(shortcuts.map { it.id }, savedOrder)
        for (item in shortcuts.sortedBy { ordered.indexOf(it.id) }) {
            val pinned = if (item.id == "numpad") BottomNumpad.get(prefs) != BottomNumpad.Mode.OFF else prefs.getBoolean(item.pref, item.defaultValue)
            val button = chip(item.glyph, context.getString(R.string.sv_bottom_toggle_desc, item.glyph), pinned)
            button.setOnClickListener { switch(item) }
            button.setOnLongClickListener {
                val data = ClipData.newPlainText("svk-shortcut", item.id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    button.startDragAndDrop(data, View.DragShadowBuilder(button), item.id, 0)
                } else {
                    @Suppress("DEPRECATION")
                    button.startDrag(data, View.DragShadowBuilder(button), item.id, 0)
                }
                true
            }
            button.setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                    DragEvent.ACTION_DROP -> {
                        val from = event.localState as? String ?: return@setOnDragListener false
                        move(from, item.id)
                        true
                    }
                    else -> true
                }
            }
            bar.addView(button)
        }
        bar.addView(chip("✓", context.getString(R.string.sv_functions_done)).apply {
            setOnClickListener { hide() }
        })
    }

    companion object {
        fun showNumpadChoices(context: Context, changed: () -> Unit) {
            val modes = BottomNumpad.Mode.entries
            val labels = arrayOf(context.getString(R.string.sv_numpad_off),
                context.getString(R.string.sv_numpad_extra), context.getString(R.string.sv_numpad_replace))
            // Stay in the current IME window: no Activity focus or service Dialog token.
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(SvUi.panelBackground())
                setPadding(SvUi.dp(context, 10), 0, SvUi.dp(context, 10), 0)
            }
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(SvUi.iconButton(context, R.drawable.ic_sv_back, context.getString(R.string.sv_back)) { SmartUi.closeInlinePanel() })
            header.addView(SvUi.label(context, 17f, bold = true).apply { setText(R.string.sv_numpad_placement) })
            column.addView(header)
            val scroll = android.widget.ScrollView(context)
            val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            modes.forEachIndexed { index, mode ->
                choices.addView(SvUi.chip(context, (if (BottomNumpad.get(context.prefs()) == mode) "✓  " else "") + labels[index]).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setOnClickListener {
                        BottomNumpad.set(context.prefs(), mode)
                        SmartUi.closeInlinePanel()
                        changed()
                    }
                }, LinearLayout.LayoutParams(-1, SvUi.dp(context, 56)).apply { bottomMargin = SvUi.dp(context, 8) })
            }
            scroll.addView(choices)
            column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            SmartUi.openInlinePanel(column)
        }
    }
}
