// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.emoji

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.smart.ui.SmartUi
import helium314.keyboard.smart.ui.StickerStudioView

/** Android document picker gives access to just the explicitly selected GIF. */
class GifImportActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/gif"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 1)
        } catch (_: android.content.ActivityNotFoundException) { finish() }
    }

    @Deprecated("Platform callback retained for the minimal non-Compose picker Activity")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        val uri = data?.data
        if (request != 1 || result != RESULT_OK || uri == null) { finish(); return }
        // Copy before finishing: transient URI permission must remain valid for this worker.
        StickerStore.worker.execute {
            val success = runCatching { StickerStore.importGif(this, uri) }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (success) R.string.sv_gif_saved else R.string.sv_media_error, Toast.LENGTH_LONG).show()
                if (success) {
                    val pkg = intent.getStringExtra("targetPackage")
                    if (pkg != null) pending = Pending(pkg, intent.getIntExtra("targetField", 0), SystemClock.elapsedRealtime())
                }
                finish()
            }
        }
    }

    companion object {
        private data class Pending(val pkg: String, val field: Int, val time: Long)
        private var pending: Pending? = null
        fun open(ime: LatinIME) {
            pending = null
            val editor = ime.currentInputEditorInfo ?: return
            ime.startActivity(Intent(ime, GifImportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("targetPackage", editor.packageName).putExtra("targetField", editor.fieldId))
        }
        @JvmStatic fun onReturn(ime: LatinIME) {
            val item = pending ?: return
            val editor = ime.currentInputEditorInfo ?: return
            if (editor.packageName == ime.packageName && editor.packageName != item.pkg) return
            pending = null
            if (editor.packageName == item.pkg && editor.fieldId == item.field && SystemClock.elapsedRealtime() - item.time < 120000)
                SmartUi.openInlinePanel(StickerStudioView(ime, library = true, gifs = true))
        }
    }
}
