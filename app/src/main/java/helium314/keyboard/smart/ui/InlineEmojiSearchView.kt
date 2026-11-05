// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.content.Context
import android.widget.*
import android.view.ViewGroup
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.emoji.EmojiViewCallback

/** Local query keyboard: never opens a second IME connection or changes the host's focus. */
class InlineEmojiSearchView(context: Context, keys: List<Key>, private val callback: EmojiViewCallback,
    private val close: () -> Unit) : LinearLayout(context) {
    private var query = ""
    private var russian = true
    private val label = SvUi.label(context, 16f)
    private val results = GridLayout(context).apply { columnCount = 8 }
    private val letters = LinearLayout(context).apply { orientation = VERTICAL }
    private var catalog = emptyList<Triple<Key, String, String>>()
    private val indexWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun index(keys: List<Key>) {
        if (indexWorker.isShutdown) return
        indexWorker.execute {
            val json = context.assets.open("emoji_search_keywords.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
            val built = keys.distinctBy { it.label }.mapNotNull { key ->
                val value = (key.outputText ?: key.label ?: "").toString()
                if (value.isEmpty()) return@mapNotNull null
                val neutral = value.replace("\uFE0F", "")
                val name = buildList {
                    var offset = 0
                    while (offset < value.length) {
                        val code = value.codePointAt(offset)
                        Character.getName(code)?.let { add(it) }
                        offset += Character.charCount(code)
                    }
                }.joinToString(" ")
                Triple(key, value, (json.optString(value) + " " + json.optString(neutral) + " " + name).lowercase())
            }
            post { if (isAttachedToWindow) { catalog = built; refresh() } }
        }
    }
    override fun onDetachedFromWindow() { indexWorker.shutdownNow(); super.onDetachedFromWindow() }
    init {
        orientation = VERTICAL
        setBackgroundColor(SvUi.panelBackground())
        val header = LinearLayout(context)
        header.addView(SvUi.iconButton(context, helium314.keyboard.latin.R.drawable.ic_arrow_back, "Назад") { close() })
        header.addView(label, LayoutParams(0, SvUi.dp(context, 44), 1f))
        header.addView(SvUi.iconButton(context, helium314.keyboard.latin.R.drawable.sym_keyboard_delete_lxx, "Удалить символ") {
            if (query.isNotEmpty()) query = query.dropLast(Character.charCount(query.codePointBefore(query.length)))
            refresh()
        })
        addView(header)
        addView(ScrollView(context).apply { addView(results) }, LayoutParams(-1, 0, 1f))
        addView(letters)
        buildLetters(); refresh(); post { index(keys) }
    }
    private fun buildLetters() {
        letters.removeAllViews()
        val rows = if (russian) listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбюё") else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEach { chars ->
            val row = LinearLayout(context)
            chars.forEach { c -> row.addView(SvUi.chip(context, c.toString()).apply {
                setPadding(0,0,0,0); setOnClickListener { query += c; refresh() }
            }, LayoutParams(0, SvUi.dp(context, 36), 1f)) }
            letters.addView(row)
        }
        val row = LinearLayout(context)
        row.addView(SvUi.chip(context, "RU / EN").apply { setOnClickListener { russian = !russian; buildLetters() } }, LayoutParams(0, SvUi.dp(context, 36), 1f))
        row.addView(SvUi.chip(context, "Пробел").apply { setOnClickListener { query += " "; refresh() } }, LayoutParams(0, SvUi.dp(context, 36), 2f))
        letters.addView(row)
    }
    private fun refresh() {
        label.text = query.ifEmpty { "Поиск эмодзи" }
        results.removeAllViews()
        val matches = catalog.filter { query.isBlank() || query.trim().split(Regex("\\s+")).all { word -> it.third.contains(word.lowercase()) } }.take(80)
        matches.forEach { (key, value, _) ->
            results.addView(SvUi.label(context, 27f).apply {
                text = value; contentDescription = callback.getDescription(value) ?: value
                setOnClickListener { callback.onReleaseKey(key) }
            }, GridLayout.LayoutParams().apply { width = 0; height = SvUi.dp(context, 42); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
        }
        if (matches.isEmpty()) results.addView(SvUi.label(context, 14f).apply { text = if (catalog.isEmpty()) "Загрузка…" else "Ничего не найдено" }, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, 8) })
    }
}
