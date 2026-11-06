package helium314.keyboard.smart

import android.content.ClipDescription
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.smart.clipboard.ScreenshotHistory
import org.junit.Assert.*
import helium314.keyboard.latin.utils.prefs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistentClipboardTest {
    @Test fun mixedHistorySurvivesReloadAndPinsSurviveClearing() {
        val context = ApplicationProvider.getApplicationContext<App>()
        context.prefs().edit().putBoolean(helium314.keyboard.latin.settings.Settings.PREF_CLIPBOARD_USE_FILES, true)
            .putInt(helium314.keyboard.latin.settings.Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, 20).commit()
        // Simulate a fresh process, independent of other Robolectric service sandboxes.
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        helium314.keyboard.latin.database.Database::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            (get(null) as? android.database.sqlite.SQLiteOpenHelper)?.close()
            set(null, null)
        }
        val dao = ClipboardDao.getInstance(context)!!
        dao.clear()
        val file = java.io.File(context.cacheDir, "real-test.png")
        android.graphics.Bitmap.createBitmap(12,12,android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        val now = System.currentTimeMillis()
        dao.addClip(now, false, "same label")
        dao.addClipUri(now+1, false, Uri.fromFile(file), ClipDescription("same label",arrayOf("image/png")), context)
        dao.addClip(now+2, false, "second text")
        assertEquals(3, dao.count())
        val image = dao.getAll().single { it.filename != null }
        assertTrue(java.io.File(ClipboardDao.clipFilesDir,image.filename!!).isFile)
        dao.setPinned(listOf(image.id), true)
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null,null)
        val loaded = ClipboardDao.getInstance(context)!!
        assertEquals(loaded.getAll().joinToString { "${it.text}:${it.filename}" } + " files=" + ClipboardDao.clipFilesDir.listFiles()?.map { it.name }, 3, loaded.count())
        loaded.clearNonPinned()
        assertEquals(image.id, loaded.getAll().single().id)
        assertTrue(loaded.getAll().single().isPinned)
    }
    @Test fun screenshotFilterDoesNotImportGallery() {
        assertTrue(ScreenshotHistory.isScreenshot("Screenshot_20261105.png","Pictures/Screenshots/"))
        assertFalse(ScreenshotHistory.isScreenshot("IMG_0042.jpg","DCIM/Camera/"))
    }
}
