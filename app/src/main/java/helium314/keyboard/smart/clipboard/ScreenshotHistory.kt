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
import helium314.keyboard.latin.utils.prefs

/** Opt-in observation of NEW screenshots only; no background gallery import. */
class ScreenshotHistory(private val context: Context, private val allowed: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var registered = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { handler.removeCallbacks(scan); handler.postDelayed(scan, 1000) }
    }
    private val scan = Runnable { worker.execute { collect() } }
    fun start() {
        if (registered || !context.prefs().getBoolean(ENABLED, false) || !permitted(context)) return
        registered = true
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }
    fun close() { if (registered) context.contentResolver.unregisterContentObserver(observer); handler.removeCallbacks(scan); worker.shutdownNow() }
    private fun collect() {
        if (!allowed() || !context.prefs().getBoolean(ENABLED, false)) return
        val prefs = context.prefs()
        val since = prefs.getLong(SINCE, System.currentTimeMillis() / 1000)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val path = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        try {
            context.contentResolver.query(collection, arrayOf("_id", "_display_name", "date_added", path),
                "date_added >= ?", arrayOf(since.toString()), "_id ASC")?.use { cursor ->
                var last = prefs.getLong(LAST, 0)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id <= last) continue
                    val name = cursor.getString(1).orEmpty(); val folder = cursor.getString(3).orEmpty()
                    if (!isScreenshot(name, folder)) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    if (Build.VERSION.SDK_INT >= 29) {
                        context.contentResolver.query(uri, arrayOf("is_pending"), null, null, null)?.use {
                            if (it.moveToFirst() && it.getInt(0) != 0) return
                        }
                    }
                    if (!allowed()) return
                    val saved = ClipboardDao.getInstance(context)?.addClipUri(cursor.getLong(2)*1000, false, uri,
                        ClipDescription(name, arrayOf(context.contentResolver.getType(uri) ?: "image/png")), context) == true
                    if (saved) last = id
                }
                prefs.edit().putLong(LAST, last).apply()
            }
        } catch (_: SecurityException) { /* Permission revoked or selected-photo access only. */ }
    }
    companion object {
        const val ENABLED = "sv_new_screenshots"
        const val SINCE = "sv_screenshot_since"
        const val LAST = "sv_screenshot_id"
        fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        fun permitted(context: Context) = Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
        fun isScreenshot(name: String, path: String): Boolean =
            Regex("(?i)(screenshots?|screen[_ -]?captures?|снимки экрана|скриншот)").containsMatchIn(path + "/" + name)
    }
}

class ScreenshotPermissionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        android.app.AlertDialog.Builder(this).setTitle("Новые снимки экрана")
            .setMessage("Добавлять только новые скриншоты в историю буфера? Нужен доступ к изображениям. Старые фото не импортируются. На Android 14+ доступ только к выбранным фото не позволяет обнаруживать будущие снимки. Без доступа скопируйте снимок в буфер вручную.")
            .setPositiveButton("Включить") { _, _ ->
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
        var last = 0L
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), null, null, "_id DESC")?.use { if (it.moveToFirst()) last = it.getLong(0) }
        prefs().edit().putLong(ScreenshotHistory.LAST, last).putBoolean(ScreenshotHistory.ENABLED, true).apply(); finish()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) enable() else finish()
    }
}
