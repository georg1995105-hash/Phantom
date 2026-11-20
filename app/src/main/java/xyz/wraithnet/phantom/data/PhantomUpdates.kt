package xyz.wraithnet.phantom.data

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInstaller
import xyz.wraithnet.phantom.BuildConfig
import xyz.wraithnet.phantom.service.PhantomUpdateInstallReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.sync.Mutex

data class PhantomRelease(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

/** Verified self-updates from the project's published GitHub Releases. */
object PhantomUpdates {
    private const val API = "https://api.github.com/repos/georg1995105-hash/Phantom/releases?per_page=50"
    internal fun assetFor(abis: List<String>, channel: String = "stable"): String? =
        if (abis.any { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") })
            if (channel == "beta") "phantom-beta-universal-release.apk"
            else "phantom-universal-release.apk"
        else null
    private const val PREFIX = "https://github.com/georg1995105-hash/Phantom/releases/download/"

    // Stable never accepts beta/pre-release assets even when tagged as X.Y.Z.
    internal fun eligibleForStable(draft: Boolean, prerelease: Boolean): Boolean =
        !draft && !prerelease
    internal fun eligibleForBeta(draft: Boolean, prerelease: Boolean, version: String): Boolean =
        !draft && prerelease && betaParts(version) != null

    internal fun isNewerForChannel(remote: String, installed: String, channel: String): Boolean {
        if (channel != "beta") return isNewer(remote, installed)
        val a = betaParts(remote) ?: return false
        val b = betaParts(installed) ?: return false
        return compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] }, { it[3] }) > 0
    }

    private fun betaParts(value: String): List<Int>? {
        val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)-beta\.(\d+)$""")
            .matchEntire(value.trim()) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    private val downloadMutex = Mutex()

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

    suspend fun check(context: Context): PhantomRelease? = PhantomHttp.get(
        context = context,
        url = API,
        headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to "PhantomVPN-Android",
        ),
    ) { response ->
        val installed = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        val stream = response.body?.byteStream() ?: error("GitHub не вернул данные обновлений")
        val releases = JSONArray(stream.use {
            PhantomHttp.readUtf8Limited(it, 2 * 1024 * 1024)
        })
        val channel = BuildConfig.UPDATE_CHANNEL
        val assetName = assetFor(Build.SUPPORTED_ABIS.toList(), channel) ?: return@get null
        var best: PhantomRelease? = null
        for (i in 0 until releases.length()) {
            val item = releases.getJSONObject(i)
            val version = item.optString("tag_name").removePrefix("v")
            val eligible = if (channel == "beta")
                eligibleForBeta(item.optBoolean("draft"), item.optBoolean("prerelease"), version)
            else eligibleForStable(item.optBoolean("draft"), item.optBoolean("prerelease")) &&
                parts(version) != null
            if (!eligible || !isNewerForChannel(version, installed, channel)) continue
            if (best != null && !isNewerForChannel(version, best.version, channel)) continue
            val assets = item.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") != assetName || asset.optString("state") != "uploaded") continue
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
    }

    suspend fun download(
        context: Context,
        release: PhantomRelease,
        onProgress: (Int) -> Unit,
    ): File {
        downloadMutex.lock()
        try {
            require(release.downloadUrl.startsWith(PREFIX) &&
                release.sha256.matches(Regex("[a-f0-9]{64}"))) {
                "Источник обновления не прошёл проверку"
            }
            return PhantomHttp.get(
                context = context,
                url = release.downloadUrl,
                headers = mapOf("User-Agent" to "PhantomVPN-Android"),
                callTimeoutMs = 0L, // Large APKs: per-read timeout, no fixed total-time cutoff.
            ) { response ->
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "phantom-" + release.version + ".apk")
                val temp = File(directory, "phantom-" + release.version + ".part")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val stream = response.body?.byteStream()
                        ?: error("GitHub вернул пустой файл обновления")
                    temp.outputStream().use { output ->
                        stream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                count += n
                                require(count <= 250L * 1024 * 1024) {
                                    "Размер обновления превышает предел"
                                }
                                if (release.sizeBytes > 0L) {
                                    onProgress((100L * count / release.sizeBytes)
                                        .toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                    require(count > 1024L &&
                        (release.sizeBytes <= 0L || count == release.sizeBytes)) {
                        "Файл обновления загружен не полностью"
                    }
                    val actual = digest.digest().joinToString("") {
                        (it.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                    require(actual.equals(release.sha256, ignoreCase = true)) {
                        "Контрольная сумма APK не совпадает"
                    }
                    verifyApk(context, temp, release.version)
                    if (target.exists()) target.delete()
                    require(temp.renameTo(target)) { "Не удалось сохранить проверенный APK" }
                    onProgress(100)
                    target
                } finally {
                    if (temp.exists()) temp.delete()
                }
            }
        } finally {
            downloadMutex.unlock()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, expectedVersion: String) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Установочный пакет повреждён")
        @Suppress("DEPRECATION")
        val current = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) {
            "Идентификатор приложения в APK не совпадает"
        }
        require(archive.versionName == expectedVersion) {
            "Версия APK не совпадает с опубликованным обновлением"
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode
            else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode
            else current.versionCode.toLong()
        require(archiveCode > installedCode) {
            "Скачанная версия не новее установленной"
        }
        val currentKeys = if (Build.VERSION.SDK_INT >= 28)
            current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else current.signatures?.map { it.toCharsString() }?.toSet()
        val nextKeys = if (Build.VERSION.SDK_INT >= 28)
            archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else archive.signatures?.map { it.toCharsString() }?.toSet()
        require(!currentKeys.isNullOrEmpty() && currentKeys == nextKeys) {
            "Подпись обновления не совпадает с установленным приложением"
        }
    }

    /**
     * Attempt self-update through PackageInstaller (Android 12+).
     * The platform may still require confirmation, handled by a receiver notification.
     * Slow APK I/O must never run on the main thread.
     */
    suspend fun openInstaller(context: Context, apk: File): Boolean = withContext(Dispatchers.IO) {
        require(apk.isFile && apk.length() > 1024L) { "APK не найден" }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return@withContext false
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val installer = context.packageManager.packageInstaller
            var sessionId = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(apk.length())
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        apk.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        session.fsync(output)
                    }
                    val callback = Intent(context, PhantomUpdateInstallReceiver::class.java)
                        .setAction(PhantomUpdateInstallReceiver.ACTION_RESULT)
                    val pending = PendingIntent.getBroadcast(
                        context, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pending.intentSender)
                }
                return@withContext true
            } catch (_: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                // OEMs may reject no-action sessions; use the normal system UI instead.
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
        true
    }
}
