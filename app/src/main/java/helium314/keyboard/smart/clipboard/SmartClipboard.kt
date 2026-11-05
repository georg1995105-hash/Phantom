// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.clipboard

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Patterns
import android.widget.ImageView
import android.widget.Toast
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

enum class ClipKind { TEXT, LINK, IMAGE, FILE }

enum class ClipCategory { ALL, TEXT, IMAGES, LINKS, PINNED }

/** Clipboard helpers: categories, thumbnails and the share fallback for images. */
object SmartClipboard {
    private const val TAG = "SmartClipboard"

    fun kindOf(entry: ClipboardHistoryEntry): ClipKind {
        if (entry.filename != null)
            return if (entry.mimeTypes?.any { it.startsWith("image/") } == true) ClipKind.IMAGE else ClipKind.FILE
        return if (isLink(entry.text)) ClipKind.LINK else ClipKind.TEXT
    }

    fun isLink(text: String?): Boolean {
        val t = text?.trim() ?: return false
        if (t.isEmpty() || t.length > 2048 || t.any { it.isWhitespace() }) return false
        return Patterns.WEB_URL.matcher(t).matches() || Patterns.EMAIL_ADDRESS.matcher(t).matches()
    }

    fun matches(entry: ClipboardHistoryEntry, category: ClipCategory) = when (category) {
        ClipCategory.ALL -> true
        ClipCategory.TEXT -> kindOf(entry) == ClipKind.TEXT
        ClipCategory.IMAGES -> kindOf(entry) == ClipKind.IMAGE
        ClipCategory.LINKS -> kindOf(entry) == ClipKind.LINK
        ClipCategory.PINNED -> entry.isPinned
    }

    // ---------- thumbnails ----------

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt().coerceAtMost(24 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun clearThumbnails() = cache.evictAll()

    /** Loads a thumbnail of a stored clip file into [view] asynchronously; [targetPx] is the wanted width. */
    fun loadThumbnail(view: ImageView, filename: String, targetPx: Int, onResult: ((Boolean) -> Unit)? = null) {
        val key = "$filename@$targetPx"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); onResult?.invoke(true); return }
        view.setImageDrawable(null)
        executor.execute {
            val bmp = decodeFile(File(ClipboardDao.clipFilesDir, filename), targetPx)
            if (bmp != null) cache.put(key, bmp)
            mainHandler.post {
                if (view.tag != key) return@post
                if (bmp != null) view.setImageBitmap(bmp)
                onResult?.invoke(bmp != null)
            }
        }
    }

    fun decodeFile(file: File, targetPx: Int): Bitmap? {
        if (!file.isFile || file.length() == 0L || targetPx < 1) return null
        return try {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opt)
        if (opt.outWidth <= 0 || opt.outHeight <= 0) return null
        var sample = 1
        while (opt.outWidth / (sample * 2) >= targetPx && opt.outHeight / (sample * 2) >= targetPx / 2) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        Log.w(TAG, "could not decode clip image", e)
        null
    }

    }

    /** Small synchronous decode of a content uri, e.g. the current primary clip; null if not readable. */
    fun decodeUri(context: Context, uri: Uri, targetPx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = max(1, max(info.size.width, info.size.height) / max(1, targetPx))
                decoder.setTargetSize(max(1, info.size.width / scale), max(1, info.size.height / scale))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read clip uri")
        null
    }

    // ---------- fallback ----------

    /** The field does not accept this content type: offer the system share sheet instead of pretending it was pasted. */
    fun shareFallback(context: Context, uri: Uri, description: ClipDescription) {
        try {
            val type = (0 until description.mimeTypeCount).map { description.getMimeType(it) }.firstOrNull { it.startsWith("image/") } ?: "image/*"
            val send = Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, "Image", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.sv_clip_share_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Toast.makeText(context, R.string.sv_clip_image_not_supported, Toast.LENGTH_LONG).show()
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.w(TAG, "share fallback failed", e)
            Toast.makeText(context, R.string.sv_clip_image_not_supported, Toast.LENGTH_LONG).show()
        }
    }
}
