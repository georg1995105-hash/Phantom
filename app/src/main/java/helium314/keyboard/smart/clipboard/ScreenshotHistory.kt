// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.clipboard

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.*
import android.provider.MediaStore
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/** Observe committed screenshots and catch up after an IME process/permission gap. */
class ScreenshotHistory(private val context: Context, private val allowed: () -> Boolean,
                        private val onSaved: () -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val queued = java.util.concurrent.atomic.AtomicBoolean()
    private var registered = false
    @Volatile private var closed = false
    @Volatile private var retryPending = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { schedule(600) }
    }
    private val scan = Runnable {
        if (!closed && queued.compareAndSet(false, true)) runCatching { worker.execute {
            try { collect() } finally {
                queued.set(false)
                if (retryPending && !closed) { retryPending = false; schedule(1500) }
            }
        } }.onFailure { queued.set(false) }
        else if (!closed) retryPending = true
    }
    private fun schedule(delay: Long = 0) {
        if (closed) return
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, delay)
    }
    fun start() {
        if (closed || !context.prefs().getBoolean(ENABLED, false) || !permitted(context)) return
        if (!registered) runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            registered = true
        }
        // ContentObserver does not replay notifications missed while the process was dead,
        // the keyboard hidden or permission UI open. Reopening must reconcile MediaStore.
        schedule()
    }
    fun close() {
        closed = true
        if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false; handler.removeCallbacks(scan); worker.shutdownNow()
    }
    private fun collect() {
        val prefs = context.prefs()
        if (closed || !prefs.getBoolean(ENABLED, false) || !permitted(context)) return
        val now = System.currentTimeMillis() / 1000
        val since = maxOf(prefs.getLong(SINCE, now), now - 24 * 60 * 60)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val path = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val columns = mutableListOf("_id", "_display_name", "date_added", path)
        if (Build.VERSION.SDK_INT >= 29) columns.add("is_pending")
        var last = prefs.getLong(LAST, 0)
        var savedAny = false
        try {
            context.contentResolver.query(collection, columns.toTypedArray(),
                "date_added >= ? AND _id > ?", arrayOf(since.toString(), last.toString()), "_id ASC")?.use { cursor ->
                var handled = 0
                while (!closed && cursor.moveToNext()) {
                    // Bound one worker job; an unrelated gallery import cannot monopolize the IME.
                    if (++handled > 200) { retryPending = true; break }
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty(); val folder = cursor.getString(3).orEmpty()
                    val maySave = allowed() && prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)
                    if (isScreenshot(name, folder) && maySave) {
                        if (Build.VERSION.SDK_INT >= 29 && cursor.getInt(4) != 0) {
                            // Do not advance past a pending screenshot. Its publish notification
                            // or the next keyboard opening will retry it after the pixels exist.
                            break
                        }
                        val uri = ContentUris.withAppendedId(collection, id)
                        val saved = ClipboardDao.getInstance(context)?.addClipUri(cursor.getLong(2) * 1000, false, uri,
                            ClipDescription(LABEL, arrayOf(context.contentResolver.getType(uri) ?: "image/png")), context) == true
                        if (!saved) break
                        savedAny = true
                    }
                    // Never import screenshots seen while a protected/no-learning field is active
                    // later merely because the user switches back to a normal editor.
                    last = id
                }
            }
            if (!closed) prefs.edit().putLong(LAST, last).apply()
            if (savedAny && !closed) handler.post { if (!closed && allowed()) onSaved() }
        } catch (_: Exception) { /* Provider restart/revoked grant: retry next opening or notification. */ }
    }
    companion object {
        const val ENABLED = "sv_new_screenshots"
        const val SINCE = "sv_screenshot_since"
        const val LAST = "sv_screenshot_id"
        const val LABEL = "Скриншот"
        fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        fun permitted(context: Context) = Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
        private val screenshotName = Regex("(?i)(screenshots?|screen[_ -]?captures?|снимки экрана|скриншот)")
        fun isScreenshot(name: String, path: String): Boolean = screenshotName.containsMatchIn(path + "/" + name)
    }
}

class ScreenshotPermissionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        android.app.AlertDialog.Builder(this).setTitle("Новые снимки экрана")
            .setMessage("Добавлять только новые скриншоты в историю буфера? Нужен доступ к изображениям. Старые фото не импортируются. На Android 14+ доступ только к выбранным фото не позволяет обнаруживать будущие снимки. Без доступа скопируйте снимок в буфер вручную.")
            .setPositiveButton("Включить") { _, _ ->
                if (!prefs().getBoolean(ScreenshotHistory.ENABLED, false))
                    prefs().edit().putLong(ScreenshotHistory.SINCE, System.currentTimeMillis()/1000).putLong(ScreenshotHistory.LAST, 0).apply()
                if (ScreenshotHistory.permitted(this)) enable() else requestPermissions(arrayOf(ScreenshotHistory.permission()), 31)
            }.setNeutralButton("Выбрать снимок") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 32)
            }.setNegativeButton("Выключить") { _, _ -> prefs().edit().putBoolean(ScreenshotHistory.ENABLED, false).apply(); finish() }
            .setOnCancelListener { finish() }.show()
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        val uri = data?.data
        if (request != 32 || result != RESULT_OK || uri == null) { finish(); return }
        // Explicit document selection works without gallery-wide access. Copy while
        // this Activity still owns the transient URI grant, then release the Activity.
        Thread {
            val saved = ClipboardDao.getInstance(this)?.addClipUri(System.currentTimeMillis(), false, uri,
                ClipDescription("Изображение", arrayOf(contentResolver.getType(uri) ?: "image/png")), this) == true
            runOnUiThread {
                android.widget.Toast.makeText(this, if (saved) "Сохранено в истории" else "Изображение недоступно или превышает лимит", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
    private fun enable() {
        Thread {
            val ready = runCatching {
                if (!prefs().getBoolean(ScreenshotHistory.ENABLED, false)) {
                    var last = 0L
                    contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), null, null, "_id DESC")?.use {
                        if (it.moveToFirst()) last = it.getLong(0)
                    }
                    prefs().edit().putLong(ScreenshotHistory.LAST, last)
                        .putLong(ScreenshotHistory.SINCE, System.currentTimeMillis()/1000).apply()
                }
                prefs().edit().putBoolean(ScreenshotHistory.ENABLED, true).apply()
            }.isSuccess
            runOnUiThread {
                android.widget.Toast.makeText(this, if (ready) "Новые скриншоты появятся в буфере клавиатуры" else "Нет доступа к новым снимкам. Выберите изображение вручную", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (ScreenshotHistory.permitted(this)) enable() else {
            android.widget.Toast.makeText(this, "Для будущих скриншотов нужен доступ ко всем фото. Можно выбрать снимок вручную", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
