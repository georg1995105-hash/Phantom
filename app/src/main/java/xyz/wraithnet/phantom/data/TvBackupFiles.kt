package xyz.wraithnet.phantom.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStreamReader

/**
 * Android TV has no guaranteed ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT handler.
 * Store an ordinary, unencrypted JSON file in public Downloads when MediaStore supports
 * it, with an app-specific external Documents directory as a legacy / TV-box fallback.
 * Never touch or replace existing subscription data until the user confirms restore.
 */
object TvBackupFiles {
    private const val PREFIX = "phantom-backup-"
    private const val SUFFIX = ".json"
    private const val DIR = "Phantom"

    private fun fileName(): String = PREFIX + System.currentTimeMillis() + SUFFIX

    private fun privateDirectory(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir, DIR).apply { check(isDirectory || mkdirs()) {
            "Нет доступной папки для резервной копии"
        } }

    /** Returns a human-readable destination, not a content URI containing any secrets. */
    fun export(context: Context): String {
        val content = PhantomBackup.capture(context)
        val filename = fileName()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            // Some low-cost TV firmware has no functioning MediaStore Downloads.
            // Fall back to the app's Documents folder; never report success if bytes
            // cannot be written.
            val saved = runCatching {
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Нет хранилища загрузок")
                try {
                    resolver.openOutputStream(uri, "w")?.use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("Не удалось записать копию")
                    val complete = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    check(resolver.update(uri, complete, null, null) > 0) {
                        "Не удалось завершить запись"
                    }
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                "Загрузки/$DIR/$filename"
            }.getOrNull()
            if (saved != null) return saved
        }
        val file = File(privateDirectory(context), filename)
        file.outputStream().use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return file.absolutePath
    }

    /** Reads the newest Phantom JSON backup without touching installed subscriptions. */
    fun importNewest(context: Context): PhantomBackup.Snapshot {
        if (Build.VERSION.SDK_INT >= 29) {
            val json = runCatching {
                val resolver = context.contentResolver
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    arrayOf("$PREFIX%$SUFFIX"),
                    MediaStore.MediaColumns.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) continue
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                        val text = resolver.openInputStream(uri)?.use { input ->
                            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                                ConfigurationFiles.read(reader, PhantomBackup.MAX_SIZE)
                            }
                        }
                        if (text != null) return@use text
                    }
                    null
                }
            }.getOrNull()
            if (json != null) return PhantomBackup.parse(json)
        }
        val file = privateDirectory(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?: error("Копия Phantom не найдена в Загрузках или папке приложения")
        return InputStreamReader(file.inputStream(), Charsets.UTF_8).use { reader ->
            PhantomBackup.parse(ConfigurationFiles.read(reader, PhantomBackup.MAX_SIZE))
        }
    }
}
