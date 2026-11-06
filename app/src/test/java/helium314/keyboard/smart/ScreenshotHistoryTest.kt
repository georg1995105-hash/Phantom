// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.smart.clipboard.ScreenshotHistory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScreenshotHistoryTest {
    private val context = ApplicationProvider.getApplicationContext<App>()
    private lateinit var dao: ClipboardDao
    private lateinit var source: File
    private val now get() = System.currentTimeMillis() / 1000
    private var pending = false
    private val provider = object : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
            MatrixCursor(projection!!).apply {
                val rows = listOf(
                    arrayOf<Any>(1L, "Screenshot_old.png", now - 7200, "Pictures/Screenshots/", 0),
                    arrayOf<Any>(2L, "IMG_camera.png", now, "DCIM/Camera/", 0),
                    arrayOf<Any>(3L, "Screenshot_new.png", now, "Pictures/Screenshots/", if (pending) 1 else 0))
                rows.filter { (it[0] as Long) > args!![1].toLong() && (it[2] as Long) >= args[0].toLong() }.forEach(::addRow)
            }
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
    @Before fun setup() {
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        context.prefs().edit().putBoolean(ScreenshotHistory.ENABLED, true).putLong(ScreenshotHistory.SINCE, now - 10)
            .putLong(ScreenshotHistory.LAST, 0).putBoolean(Settings.PREF_CLIPBOARD_USE_FILES, true).commit()
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        helium314.keyboard.latin.database.Database::class.java.getDeclaredField("instance").apply {
            isAccessible = true; (get(null) as? android.database.sqlite.SQLiteOpenHelper)?.close(); set(null, null)
        }
        dao = ClipboardDao.getInstance(context)!!; dao.clear()
        source = File(context.cacheDir, "screenshot.png")
        android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888).let { b ->
            source.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }
    private fun collect(history: ScreenshotHistory) = ScreenshotHistory::class.java.getDeclaredMethod("collect").apply { isAccessible = true }.invoke(history)
    @Test fun catchesOnlyNewScreenshotWithoutClipboardAndDoesNotReimportDeletedEntry() {
        val history = ScreenshotHistory(context, { true })
        collect(history)
        assertEquals(1, dao.count())
        assertNotNull(dao.getAll().single().filename)
        assertEquals(ScreenshotHistory.LABEL, dao.getAll().single().text)
        dao.clear()
        collect(history)
        assertEquals(0, dao.count())
        history.close()
    }
    @Test fun pendingScreenshotRetriesWhenPublishedInsteadOfAdvancingPastIt() {
        val history = ScreenshotHistory(context, { true })
        pending = true; collect(history)
        assertEquals(0, dao.count())
        assertEquals(2, context.prefs().getLong(ScreenshotHistory.LAST, 0))
        pending = false; collect(history)
        assertEquals(1, dao.count())
        history.close()
    }
    @Test fun screenshotSeenInProtectedFieldIsNotImportedLater() {
        var allowed = false
        val history = ScreenshotHistory(context, { allowed })
        collect(history); allowed = true; collect(history)
        assertEquals(0, dao.count())
        history.close()
    }
    @Test fun openingKeyboardSchedulesCatchupWithoutAnObserverNotification() {
        val history = ScreenshotHistory(context, { true })
        history.start()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Drain the exact worker, rather than sleeping or relying on scheduler timing.
        val worker = ScreenshotHistory::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(history) as java.util.concurrent.ExecutorService
        worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, dao.count())
        history.close()
    }
    @Test fun recognizesOemScreenshotFolders() {
        assertTrue(ScreenshotHistory.isScreenshot("Screenshot_20261106_091900_com.vivo.jpg", "DCIM/Screenshots/"))
        assertTrue(ScreenshotHistory.isScreenshot("IMG_20261106.jpg", "Pictures/截屏/"))
        assertTrue(ScreenshotHistory.isScreenshot("Screen_Shot_1.png", "Pictures/"))
        assertFalse(ScreenshotHistory.isScreenshot("IMG_0042.jpg", "DCIM/Camera/"))
    }
    @Test fun statusExplainsWhyScreenshotsAreNotImported() {
        assertEquals(ScreenshotHistory.Status.ACTIVE, ScreenshotHistory.status(context))
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(ScreenshotHistory.Status.NEED_ACCESS, ScreenshotHistory.status(context))
        context.prefs().edit().putBoolean(ScreenshotHistory.ENABLED, false).commit()
        assertEquals(ScreenshotHistory.Status.OFF, ScreenshotHistory.status(context))
    }
    @Test @Config(sdk = [34]) fun selectedPhotosOnlyIsReportedAsPartialAccess() {
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(context).grantPermissions("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        assertEquals(ScreenshotHistory.Status.PARTIAL_ACCESS, ScreenshotHistory.status(context))
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(ScreenshotHistory.Status.ACTIVE, ScreenshotHistory.status(context))
    }
}
