package xyz.wraithnet.phantom.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class PhantomRelease(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

/** Verified self-updates from the project's published GitHub Releases. */
object PhantomUpdates {
    private const val API = "https://api.github.com/repos/georg1995105-hash/Phantom/releases?per_page=15"
    private const val ASSET = "phantom-arm64-v8a-release.apk"
    private const val PREFIX = "https://github.com/georg1995105-hash/Phantom/releases/download/"

    fun isNewer(remote: String, installed: String): Boolean {
        val a = parts(remote) ?: return false
        val b = parts(installed) ?: return false
        for (i in 0..2) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }

    private fun parts(value: String): List<Int>? {
        val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").matchEntire(value.trim()) ?: return null
        return m.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    suspend fun check(context: Context): PhantomRelease? = withContext(Dispatchers.IO) {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PhantomVPN-Android")
        }
        try {
            require(connection.responseCode == 200) {
                "Не удалось получить версии приложения (HTTP " + connection.responseCode + ")"
            }
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            var best: PhantomRelease? = null
            for (i in 0 until releases.length()) {
                val item = releases.getJSONObject(i)
                if (item.optBoolean("draft")) continue
                val version = item.optString("tag_name").removePrefix("v")
                if (!isNewer(version, installed)) continue
                if (best != null && !isNewer(version, best.version)) continue
                val assets = item.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    if (asset.optString("name") != ASSET || asset.optString("state") != "uploaded") continue
                    val url = asset.optString("browser_download_url")
                    val hash = asset.optString("digest").removePrefix("sha256:")
                    if (!url.startsWith(PREFIX) || !hash.matches(Regex("[a-fA-F0-9]{64}"))) continue
                    best = PhantomRelease(
                        version, url, hash.lowercase(Locale.ROOT),
                        asset.optLong("size", 0L), item.optString("body").take(1500)
                    )
                    break
                }
            }
            best
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        context: Context,
        release: PhantomRelease,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(release.downloadUrl.startsWith(PREFIX) &&
            release.sha256.matches(Regex("[a-f0-9]{64}"))) { "Источник обновления не прошёл проверку" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "phantom-" + release.version + ".apk")
        val temp = File(directory, "phantom-" + release.version + ".part")
        val conn = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PhantomVPN-Android")
        }
        try {
            require(conn.responseCode == 200) { "Ошибка загрузки: HTTP " + conn.responseCode }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            temp.outputStream().use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        count += n
                        require(count <= 250L * 1024 * 1024) { "Размер обновления превышает предел" }
                        if (release.sizeBytes > 0L) {
                            onProgress((100L * count / release.sizeBytes).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            require(count > 1024 && (release.sizeBytes <= 0 || count == release.sizeBytes)) {
                "Файл обновления загружен не полностью"
            }
            val actual = digest.digest().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            require(actual.equals(release.sha256, ignoreCase = true)) {
                "Контрольная сумма APK не совпадает"
            }
            verifyApk(context, temp)
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "Не удалось сохранить проверенный APK" }
            onProgress(100)
            target
        } finally {
            conn.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun verifyApk(context: Context, file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Установочный пакет повреждён")
        @Suppress("DEPRECATION")
        val current = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) {
            "Идентификатор приложения в APK не совпадает"
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode
            else @Suppress("DEPRECATION") archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode
            else @Suppress("DEPRECATION") current.versionCode.toLong()
        require(archiveCode > installedCode) {
            "Скачанная версия не новее установленной"
        }
        val currentKeys = current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val nextKeys = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        require(!currentKeys.isNullOrEmpty() && currentKeys == nextKeys) {
            "Подпись обновления не совпадает с установленным приложением"
        }
    }

    /** Returns false when Android requires user permission for this APK installer. */
    fun openInstaller(context: Context, apk: File): Boolean {
        require(apk.exists() && apk.length() > 0) { "APK не найден" }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".updates", apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }
}
