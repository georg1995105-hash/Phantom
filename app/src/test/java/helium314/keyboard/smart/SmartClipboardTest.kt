// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.smart.clipboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmartClipboardTest {
    private fun clip(text: String? = null, file: String? = null, mime: List<String>? = null, pinned: Boolean = false) =
        ClipboardHistoryEntry(1, 0, pinned, text, file, mime)
    @Test fun mimeDeterminesImageCategory() {
        val image = clip("caption", "stored.png", listOf("image/png"))
        assertTrue(SmartClipboard.matches(image, ClipCategory.IMAGES))
        assertFalse(SmartClipboard.matches(image, ClipCategory.TEXT))
        assertFalse(SmartClipboard.matches(clip("photo.png"), ClipCategory.IMAGES))
        assertFalse(SmartClipboard.matches(clip(file="a.pdf", mime=listOf("application/pdf")), ClipCategory.IMAGES))
    }
    @Test fun urlsAndPinnedAreIndependentCategories() {
        val url = clip("https://developer.android.com/", pinned=true)
        assertTrue(SmartClipboard.matches(url, ClipCategory.LINKS))
        assertTrue(SmartClipboard.matches(url, ClipCategory.PINNED))
        assertFalse(SmartClipboard.matches(clip("See https://example.com in this text"), ClipCategory.LINKS))
        assertFalse(SmartClipboard.matches(clip("plain"), ClipCategory.PINNED))
    }
    @Test fun missingImageFailsWithoutFakeContent() {
        assertNull(SmartClipboard.decodeFile(java.io.File("/does-not-exist.png"), 180))
    }
}
