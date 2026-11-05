package helium314.keyboard.smart.debug

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.inputmethodservice.InputMethodService
import android.os.PersistableBundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import helium314.keyboard.keyboard.KeyboardView
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import java.io.File

/**
 * Debug-only helpers for emulator testing, where `screencap` only returns black frames:
 * `adb shell am broadcast -a ru.smartvoice.DEBUG_SNAPSHOT --es name x` renders the IME window into a PNG,
 * `adb shell am broadcast -a ru.smartvoice.DEBUG_CLIP --es text "…"` (or `--ez image true`, `--ez sensitive true`)
 * puts a test clip into the system clipboard.
 */
object DebugSnapshot {
    private const val ACTION = "ru.smartvoice.DEBUG_SNAPSHOT"
    private const val ACTION_UI = "ru.smartvoice.DEBUG_UI"
    private const val ACTION_CLIP = "ru.smartvoice.DEBUG_CLIP"
    private const val TAG = "DebugSnapshot"
    private var receiver: BroadcastReceiver? = null

    fun register(ime: InputMethodService) {
        if (!BuildConfig.DEBUG || receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_UI) {
                    val latin = ime as? helium314.keyboard.latin.LatinIME ?: return
                    // The service registers before Android has created its input view. Adb callers
                    // must wait for the visible keyboard just as a touch caller does.
                    if (helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().mainKeyboardView == null) {
                        Log.w(TAG, "Ignoring UI command before the keyboard view is ready")
                        return
                    }
                    intent.getStringExtra("type")?.codePoints()?.forEach { latin.onCodeInput(it, 0, 0, false) }
                    when (intent.getStringExtra("page")) {
                        "geometry" -> geometry(ime, intent.getStringExtra("token").orEmpty())
                        "functions" -> helium314.keyboard.smart.ui.SmartUi.toggleFunctions()
                        "emoji" -> latin.onCodeInput(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.EMOJI, 0, 0, false)
                        "clipboard" -> latin.onCodeInput(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.CLIPBOARD, 0, 0, false)
                        "alpha" -> { helium314.keyboard.smart.ui.SmartUi.hideFunctions(); latin.onCodeInput(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.ALPHA, 0, 0, false) }
                    }
                } else if (intent.action == ACTION_CLIP) setClip(ime, intent) else snapshot(ime, intent.getStringExtra("name") ?: "snap")
            }
        }
        val filter = IntentFilter(ACTION).apply { addAction(ACTION_CLIP); addAction(ACTION_UI) }
        ContextCompat.registerReceiver(ime, r, filter, android.Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    fun unregister(ime: InputMethodService) {
        receiver?.let { runCatching { ime.unregisterReceiver(it) } }
        receiver = null
    }

    /** Numeric IME geometry for adb tests; no editor text or suggestion contents. */
    private fun geometry(ime: InputMethodService, token: String) {
        val decor = ime.window?.window?.decorView ?: return
        decor.post {
            val nodes = org.json.JSONObject()
            val windowOrigin = IntArray(2)
            decor.getLocationOnScreen(windowOrigin)
            fun walk(view: View) {
                val name = if (view.id == View.NO_ID) "" else runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
                val tag = view.tag as? String ?: ""
                val key = if (tag.startsWith("sv_")) tag else name
                if (key.startsWith("sv_") || key == "keyboard_view" || key == "main_keyboard_frame") {
                    val rect = android.graphics.Rect()
                    if (view.isShown && view.getGlobalVisibleRect(rect)) {
                        // GlobalVisibleRect is relative to the ViewRoot, while adb
                        // taps use screen coordinates (the status bar offsets this window).
                        rect.offset(windowOrigin[0], windowOrigin[1])
                        nodes.put(key, org.json.JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
            walk(decor)
            val editor = ime.currentInputEditorInfo
            val result = org.json.JSONObject().put("token", token).put("nodes", nodes)
                .put("inputShown", ime.isInputViewShown).put("fieldId", editor?.fieldId ?: 0)
                .put("package", editor?.packageName.orEmpty())
            val keyboard = helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().mainKeyboardView
            val keys = org.json.JSONObject()
            if (keyboard != null && keyboard.isShown) {
                val origin = IntArray(2); keyboard.getLocationOnScreen(origin)
                keyboard.keyboard?.sortedKeys?.forEach { key ->
                    keys.put(key.code.toString(), org.json.JSONArray(listOf(origin[0] + key.x,
                        origin[1] + key.y, origin[0] + key.x + key.width, origin[1] + key.y + key.height)))
                }
            }
            result.put("keys", keys)
            runCatching { File(ime.getExternalFilesDir(null) ?: ime.filesDir, "geometry.json").writeText(result.toString()) }
        }
    }

    private fun invalidateKeys(v: View) {
        if (v is KeyboardView) v.invalidateAllKeys()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateKeys(v.getChildAt(i))
    }

    private fun snapshot(ime: InputMethodService, name: String) {
        if (!name.matches(Regex("[A-Za-z0-9_-]{1,60}"))) return
        val latin = ime as? helium314.keyboard.latin.LatinIME
        Log.i(TAG, "suggestions enabled=${helium314.keyboard.latin.settings.Settings.getValues().mSuggestionsEnabled}, count=${latin?.svSuggestionCount()}, inputType=${ime.currentInputEditorInfo?.inputType}")
        val decor = ime.window?.window?.decorView ?: return
        decor.post {
            try {
                if (decor.width <= 0 || decor.height <= 0) { Log.w(TAG, "window not laid out"); return@post }
                // keyboard views keep an offscreen buffer for software drawing that is only partially updated
                invalidateKeys(decor)
                fun inputView(view: View): View? {
                    if (view is helium314.keyboard.latin.InputView) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) inputView(view.getChildAt(i))?.let { return it }
                    return null
                }
                val target = inputView(decor) ?: decor
                // Render the actual measured keyboard frame, including overlays; the
                // transparent area reserved for popups above it is not keyboard content.
                val frame = target.findViewById<View>(R.id.main_keyboard_frame) ?: target
                val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                if (frame !== target) canvas.translate(-frame.left.toFloat(), -frame.top.toFloat())
                target.draw(canvas)
                val dir = ime.getExternalFilesDir(null) ?: ime.filesDir
                File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "saved $name")
            } catch (e: Exception) {
                Log.e(TAG, "failed", e)
            }
        }
    }

    private fun setClip(ime: InputMethodService, intent: Intent) {
        val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = if (intent.getBooleanExtra("image", false)) {
            val dir = File(ime.filesDir, "clipboard").apply { mkdirs() }
            val file = File(dir, "debug_${System.currentTimeMillis()}.png")
            val source = intent.getStringExtra("imageSource")
            val ownFiles = ime.getExternalFilesDir(null)?.canonicalPath
            val sourcePath = source?.let { File(it).canonicalPath }
            val bmp = if (sourcePath != null && (sourcePath.startsWith("/storage/emulated/0/Download/") ||
                    (ownFiles != null && sourcePath.startsWith("$ownFiles/"))))
                android.graphics.BitmapFactory.decodeFile(source, android.graphics.BitmapFactory.Options().apply { inMutable = true }) ?: return
            else Bitmap.createBitmap(900, 1500, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 900f, 1500f, Color.rgb(0x2B, 0x6C, 0xE0), Color.rgb(0xF0, 0x8A, 0x3C), Shader.TileMode.CLAMP)
            if (source == null) c.drawRect(0f, 0f, 900f, 1500f, p)
            p.shader = null
            p.color = Color.WHITE
            if (source == null) c.drawCircle(450f, 600f, 220f, p)
            p.textSize = 90f
            if (source == null) c.drawText(intent.getStringExtra("label") ?: "Test fixture", 160f, 1100f, p)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(ime, if (ime.packageName.startsWith("ru.smartvoice.keyboard.preview")) ime.packageName + ".clipprovider" else ime.getString(R.string.clipboard_provider_authority), file)
            ClipData.newUri(ime.contentResolver, "Screenshot", uri)
        } else ClipData.newPlainText("debug", intent.getStringExtra("text") ?: "")
        if (android.os.Build.VERSION.SDK_INT >= 24 && intent.getBooleanExtra("sensitive", false))
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        Log.i(TAG, "clip set")
    }
}
