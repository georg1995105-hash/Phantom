// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.clipboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.smart.ui.SvUi

/** Separate focused search field so typing a query never edits the destination message. */
class ClipboardSearchActivity : Activity(), SmartClipAdapter.Callbacks {
    private lateinit var adapter: SmartClipAdapter
    private var targetPackage: String? = null
    private var targetField = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra("targetPackage")
        targetField = intent.getIntExtra("targetField", 0)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SvUi.panelBackground())
        }
        val header = LinearLayout(this)
        header.addView(SvUi.iconButton(this, R.drawable.ic_arrow_back, getString(R.string.sv_back)) { finish() })
        val search = EditText(this).apply {
            hint = getString(R.string.sv_clip_search)
            contentDescription = hint
            setTextColor(SvUi.text())
            setHintTextColor(SvUi.secondaryText())
            isSingleLine = true
            privateImeOptions = "sv_clipboard_search"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        header.addView(search, LinearLayout.LayoutParams(0, SvUi.dp(this, 56), 1f))
        column.addView(header)
        adapter = SmartClipAdapter(this).apply { setHasStableIds(true); textSizePx = 16 * resources.displayMetrics.scaledDensity }
        column.addView(RecyclerView(this).apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            adapter = this@ClipboardSearchActivity.adapter
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        filter("")
        search.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun filter(query: String) {
        adapter.rows = ClipboardDao.getInstance(this)?.getAll().orEmpty()
            .filter { query.isBlank() || it.text.orEmpty().contains(query, ignoreCase = true) }
            .map { SmartClipAdapter.Row.Clip(it) }
        adapter.notifyDataSetChanged()
    }

    override fun onClipClick(entry: ClipboardHistoryEntry) {
        targetPackage?.let { pending = Pending(entry.id, it, targetField, SystemClock.elapsedRealtime()) }
        finish()
    }
    override fun onClipLongClick(entry: ClipboardHistoryEntry) = onClipClick(entry)

    companion object {
        private data class Pending(val id: Long, val pkg: String, val field: Int, val time: Long)
        private var pending: Pending? = null
        fun open(ime: LatinIME) {
            pending = null
            val editor = ime.currentInputEditorInfo ?: return
            // Search needs letter keys. Otherwise the clipboard palette survives the
            // new input connection and leaves the query field without a typing surface.
            ime.onCodeInput(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.ALPHA,
                helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE,
                helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE, false)
            ime.startActivity(Intent(ime, ClipboardSearchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("targetPackage", editor.packageName).putExtra("targetField", editor.fieldId))
        }
        @JvmStatic fun pasteOnReturn(ime: LatinIME) {
            val result = pending ?: return
            val editor = ime.currentInputEditorInfo ?: return
            if (editor.privateImeOptions == "sv_clipboard_search") return
            pending = null // consume once, including mismatch or timeout
            if (editor.packageName != result.pkg || editor.fieldId != result.field ||
                SystemClock.elapsedRealtime() - result.time > 30000) return
            val entry = ime.clipboardHistoryManager.getAllEntries().firstOrNull { it.id == result.id } ?: return
            if (entry.filename == null) ime.onTextInput(entry.text)
            else if (java.io.File(ClipboardDao.clipFilesDir, entry.filename).isFile)
                runCatching { ime.mKeyboardActionListener.onContent(entry.getContentInfo(ime)) }
        }
    }
}
