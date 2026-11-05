// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.content.Context
import android.widget.*
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.emoji.EmojiViewCallback
import helium314.keyboard.smart.emoji.EmojiSearchMatcher

/** Local query keyboard: never opens a second IME connection or changes the host's focus. */
class InlineEmojiSearchView(context: Context, keys: List<Key>, private val callback: EmojiViewCallback,
    private val close: () -> Unit) : LinearLayout(context) {
    private var query = ""
    private var russian = true
    private val label = SvUi.label(context, 16f)
    private val resultAdapter = ResultAdapter()
    private val results = RecyclerView(context).apply {
        layoutManager = GridLayoutManager(context, 8)
        adapter = resultAdapter
        itemAnimator = null
    }
    private val empty = SvUi.label(context, 14f)
    private val letters = LinearLayout(context).apply { orientation = VERTICAL }
    private var catalog = emptyList<Triple<Key, String, String>>()
    private val indexWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun index(keys: List<Key>) {
        if (indexWorker.isShutdown) return
        indexWorker.execute {
            val json = synchronized(keywordCache) {
                annotations ?: context.assets.open("emoji_search_keywords.json").bufferedReader().use {
                    org.json.JSONObject(it.readText()).also { parsed -> annotations = parsed }
                }
            }
            val built = keys.distinctBy { it.label }.mapNotNull { key ->
                val value = (key.outputText ?: key.label ?: "").toString()
                if (value.isEmpty()) return@mapNotNull null
                val neutral = value.replace("\uFE0F", "")
                val keywords = synchronized(keywordCache) { keywordCache.getOrPut(value) {
                val name = buildList {
                    var offset = 0
                    while (offset < value.length) {
                        val code = value.codePointAt(offset)
                        Character.getName(code)?.let { add(it) }
                        offset += Character.charCount(code)
                    }
                }.joinToString(" ")
                EmojiSearchMatcher.index(json.optString(value) + " " + json.optString(neutral) + " " + name)
                } }
                Triple(key, value, keywords)
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
        addView(empty, LayoutParams(-1, SvUi.dp(context, 28)))
        addView(results, LayoutParams(-1, 0, 1f))
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
        resultAdapter.matches = EmojiSearchMatcher.find(catalog, query) { it.third }
        resultAdapter.notifyDataSetChanged()
        empty.visibility = if (resultAdapter.matches.isEmpty()) VISIBLE else GONE
        empty.text = if (catalog.isEmpty()) "Загрузка…" else "Ничего не найдено"
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {
        var matches = emptyList<Triple<Key, String, String>>()
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
        override fun getItemCount() = matches.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(SvUi.label(context, 29f).apply {
            layoutParams = ViewGroup.LayoutParams(-1, SvUi.dp(context, 44))
            background = SvUi.ripple(null, SvUi.dp(context, 12).toFloat())
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (key, value, _) = matches[position]
            holder.text.text = value
            holder.text.contentDescription = callback.getDescription(value) ?: value
            holder.text.setOnClickListener { callback.onReleaseKey(key) }
        }
    }

    companion object {
        private var annotations: org.json.JSONObject? = null
        private val keywordCache = mutableMapOf<String, String>()
    }
}
