// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.smart.emoji.StickerStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Before fun clean() { File(context.filesDir, "stickers").deleteRecursively() }
    @After fun clear() = clean()

    @Test fun pngIs512pxAndSurvivesNewLibraryRead() {
        val item = StickerStore.create(context, "😺", "❤️")
        assertEquals("image/png", item.mime)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(item.file.path, options)
        assertEquals(512, options.outWidth)
        assertEquals(512, options.outHeight)
        val pixels = BitmapFactory.decodeFile(item.file.path)
        // A file with just the small badge is a broken sticker even if its dimensions match.
        assertTrue(android.graphics.Color.alpha(pixels.getPixel(256, 256)) > 0)
        pixels.recycle()
        assertEquals(item.file, StickerStore.list(context, false).single().file)
        assertTrue(StickerStore.list(context, true).isEmpty())
        // Repeated save deduplicates instead of consuming more library/storage slots.
        assertEquals(item.file, StickerStore.create(context, "😺", "❤️").file)
        assertEquals(1, StickerStore.list(context, false).size)
    }

    @Test fun explicitGifIsCopiedAndRemainsAfterSourceDeletion() {
        val source = File(context.cacheDir, "selected.gif")
        source.writeBytes(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", 0))
        val item = StickerStore.importGif(context, Uri.fromFile(source))
        val bytes = source.readBytes()
        source.delete()
        assertArrayEquals(bytes, item.file.readBytes())
        assertEquals("image/gif", item.mime)
        assertEquals(1, StickerStore.list(context, true).size)
        assertTrue(StickerStore.list(context, false).isEmpty())
    }

    @Test fun renamedTextIsNotAcceptedAsGif() {
        val source = File(context.cacheDir, "invalid.gif").apply { writeText("not an image") }
        try {
            StickerStore.importGif(context, Uri.fromFile(source))
            fail("Invalid GIF must not create a library entry")
        } catch (_: IllegalArgumentException) { }
        finally { source.delete() }
        assertTrue(StickerStore.list(context, true).isEmpty())
    }
}
