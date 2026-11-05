// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.emoji

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.R
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Explicitly created/imported media only; separate from clipboard retention and gallery access. */
object StickerStore {
    data class Item(val file: File, val mime: String) {
        val isGif get() = mime == "image/gif"
        fun content(context: Context): InputContentInfoCompat {
            val authority = if (context.packageName.startsWith("ru.smartvoice.keyboard.preview"))
                context.packageName + ".clipprovider" else context.getString(R.string.clipboard_provider_authority)
            val uri = FileProvider.getUriForFile(context, authority, file)
            return InputContentInfoCompat(uri, ClipDescription(if (isGif) "GIF" else "SmartVoice sticker", arrayOf(mime)), null)
        }
    }

    val worker = Executors.newSingleThreadExecutor()
    private fun directory(context: Context) = File(context.filesDir, "stickers").apply { mkdirs() }
    fun list(context: Context, gifs: Boolean): List<Item> = directory(context).listFiles().orEmpty()
        .filter { it.isFile && it.extension in setOf("png", "gif") && (it.extension == "gif") == gifs }
        .sortedByDescending { it.lastModified() }
        .map { Item(it, if (gifs) "image/gif" else "image/png") }

    /** A 512px transparent PNG rendered with Android's color emoji font. No network or model. */
    fun create(context: Context, base: String, badge: String): Item {
        require(base.isNotBlank() && base.length <= 32 && badge.length <= 32)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            drawEmoji(canvas, base, 256f, 258f, 364f)
            if (badge.isNotEmpty()) drawEmoji(canvas, badge, 390f, 395f, 172f)
            val bytes = java.io.ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
            return save(context, bytes, "png")
        } finally { bitmap.recycle() }
    }

    private fun drawEmoji(target: Canvas, emoji: String, x: Float, y: Float, size: Float) {
        // Older Skia silently omits large color-font glyphs on a software Canvas.
        // Rasterize below that limit first, then scale the bitmap, not the font.
        val stamp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 160f }
            Canvas(stamp).drawText(emoji, 128f, 128f - (paint.ascent() + paint.descent()) / 2f, paint)
            val half = size * 256f / 160f / 2f
            target.drawBitmap(stamp, null, android.graphics.RectF(x - half, y - half, x + half, y + half),
                Paint(Paint.FILTER_BITMAP_FLAG))
        } finally { stamp.recycle() }
    }

    fun importGif(context: Context, uri: Uri): Item {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32768)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(out.size() + count <= 16 * 1024 * 1024) { "GIF exceeds 16 MB" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("Unreadable GIF")
        require(bytes.size >= 13 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216)
        return save(context, bytes, "gif")
    }

    private fun save(context: Context, bytes: ByteArray, extension: String): Item {
        val folder = directory(context)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(folder, "$digest.$extension")
        if (!file.exists()) {
            require(folder.listFiles().orEmpty().count { it.extension in setOf("png", "gif") } < 120) { "Sticker library full" }
            require(folder.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= 128 * 1024 * 1024L) { "Sticker library exceeds 128 MB" }
            val temp = File.createTempFile("sticker_", ".tmp", folder)
            try { temp.writeBytes(bytes); check(temp.renameTo(file)) } finally { temp.delete() }
        }
        file.setLastModified(System.currentTimeMillis())
        return Item(file, if (extension == "gif") "image/gif" else "image/png")
    }
}
