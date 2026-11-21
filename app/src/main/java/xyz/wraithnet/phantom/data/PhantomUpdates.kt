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
import java.io.FileOutputStream
import java.io.IOException
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    // An ABI-specific APK is a complete, separately signed installable package,
    // NOT an Android split APK. The first ABI is Android's preferred native ABI.
    // Avoid transferring all four independent Go native libraries on a phone.
    internal fun assetCandidates(abis: List<String>, channel: String = "stable"): List<String> {
        val preferred = abis.firstOrNull { it in supportedAbis } ?: return emptyList()
        return if (channel == "beta") listOf("phantom-beta-universal-release.apk")
        else listOf("phantom-$preferred-release.apk", "phantom-universal-release.apk")
    }

    internal fun assetFor(abis: List<String>, channel: String = "stable"): String? =
        assetCandidates(abis, channel).firstOrNull()

    /** Select only a complete signed release asset with a verifiable digest. */
    internal fun chooseAsset(assets: JSONArray, candidates: List<String>): org.json.JSONObject? {
        for (name in candidates) {
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") != name ||
                    asset.optString("state") != "uploaded") continue
                val url = asset.optString("browser_download_url")
                val hash = asset.optString("digest").removePrefix("sha256:")
                if (!url.startsWith(PREFIX) || !hash.matches(Regex("[a-fA-F0-9]{64}"))) continue
                val size = asset.optLong("size", 0L)
                if (size <= 0L || size > 250L * 1024 * 1024) continue
                return asset
            }
        }
        return null
    }
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

    // Accept a partial GitHub CDN response only when it exactly continues
    // the verified asset length. Never append unrelated HTTP 200 content.
    internal fun validContentRange(header: String?, offset: Long, size: Long): Boolean {
        val match = Regex("""bytes (\d+)-(\d+)/(\d+)""")
            .matchEntire(header.orEmpty()) ?: return false
        val (first, last, total) = match.destructured
        val start = first.toLongOrNull() ?: return false
        val end = last.toLongOrNull() ?: return false
        return start == offset && end >= start && total.toLongOrNull() == size &&
            end < size
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
        val candidateAssets = assetCandidates(Build.SUPPORTED_ABIS.toList(), channel)
        if (candidateAssets.isEmpty()) return@get null
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
            // The latest release is chosen by version, then the smallest
            // compatible complete APK by candidate priority, not API order.
            val asset = chooseAsset(assets, candidateAssets) ?: continue
            best = PhantomRelease(
                version,
                asset.getString("browser_download_url"),
                asset.getString("digest").removePrefix("sha256:").lowercase(Locale.ROOT),
                asset.getLong("size"),
                item.optString("body").take(1500)
            )
        }
        best
    }

    suspend fun download(
        context: Context,
        release: PhantomRelease,
        onProgress: (Int) -> Unit,
        onSpeed: ((Long) -> Unit)? = null,
    ): File {
        downloadMutex.lock()
        try {
            require(release.downloadUrl.startsWith(PREFIX) &&
                release.sha256.matches(Regex("[a-f0-9]{64}"))) {
                "Источник обновления не прошёл проверку"
            }
            val size = release.sizeBytes
            require(size in 1025L..(250L * 1024 * 1024)) {
                "Размер APK в данных релиза недостоверен"
            }
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(directory, "phantom-" + release.version + ".apk")
            // Hash-qualified cache prevents mixing files from different release
            // assets (e.g. universal APK and the compact ARM64 APK).
            val temp = File(directory, "phantom-" + release.version + "-" +
                release.sha256.take(12) + ".part")
            if (temp.length() > size) temp.delete()
            val startedAt = SystemClock.elapsedRealtime()
            var attempts = 0
            var lastProgress = -1
            var transferred = 0L
            var lastSpeedUpdate = 0L
            val job = currentCoroutineContext()
            try {
                onProgress((100L * temp.length() / size).toInt().coerceIn(0, 100))
                for (attempt in 0 until 4) {
                    job.ensureActive()
                    val offset = temp.length()
                    if (offset == size) break
                    try {
                        val headers = mutableMapOf(
                            "User-Agent" to "PhantomVPN-Android",
                            "Accept-Encoding" to "identity"
                        )
                        if (offset > 0L) headers["Range"] = "bytes=${offset}-"
                        PhantomHttp.get(
                            context = context,
                            url = release.downloadUrl,
                            headers = headers,
                            callTimeoutMs = 0L,
                        ) { response ->
                            val partial = response.code == 206
                            require(!partial || (offset > 0L && validContentRange(
                                response.header("Content-Range"), offset, size))) {
                                "CDN вернул неверный диапазон APK"
                            }
                            // If CDN ignored Range, restart from byte zero;
                            // never append a complete response to old data.
                            val append = partial && offset > 0L
                            require(append || response.code == 200) {
                                "CDN не поддержал возобновление загрузки"
                            }
                            var count = if (append) offset else 0L
                            FileOutputStream(temp, append).use { output ->
                                val input = response.body?.byteStream()
                                    ?: error("GitHub вернул пустой файл обновления")
                                input.use {
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        job.ensureActive()
                                        val n = it.read(buffer)
                                        if (n == -1) break
                                        if (n == 0) continue
                                        count += n
                                        transferred += n
                                        require(count <= size) {
                                            "Размер полученных данных превышает размер APK"
                                        }
                                        output.write(buffer, 0, n)
                                        val progress = (100L * count / size).toInt()
                                        if (progress != lastProgress) {
                                            onProgress(progress)
                                            lastProgress = progress
                                        }
                                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                                        if (elapsed > 1000L && elapsed - lastSpeedUpdate >= 1000L) {
                                            onSpeed?.invoke(1000L * transferred / elapsed)
                                            lastSpeedUpdate = elapsed
                                        }
                                    }
                                }
                            }
                        }
                        // A clean EOF before the declared length can happen
                        // through a temporarily truncated network response.
                        if (temp.length() == size) break
                        attempts++
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: IOException) {
                        attempts++
                        if (attempt == 3) throw error
                    }
                    if (attempt < 3) delay(600L * (attempt + 1))
                }
                require(temp.length() == size) {
                    "Загрузка прервана: сохранено " + temp.length() + " из " + size + " байт"
                }
                // Hash the complete file, including the recovered prefix;
                // a partial/CDN corruption can never reach PackageInstaller.
                val actual = MessageDigest.getInstance("SHA-256")
                temp.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        job.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        actual.update(buffer, 0, read)
                    }
                }
                val hash = actual.digest().joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }
                require(hash == release.sha256) {
                    "Контрольная сумма APK не совпадает"
                }
                verifyApk(context, temp, release.version)
                if (target.exists()) target.delete()
                require(temp.renameTo(target)) { "Не удалось сохранить проверенный APK" }
                onProgress(100)
                val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                VpnEventLog.record(context, "update",
                    "APK ${size / 1048576} МиБ; средняя скорость " +
                        "${(transferred * 1000L / elapsed) / 1024L} КиБ/с; повторы: ${attempts}")
                return target
            } catch (error: Exception) {
                // On a transport error keep the hash-qualified partial asset
                // so the next attempt may resume safely using HTTP Range.
                if (error !is IOException && error !is kotlinx.coroutines.CancellationException) {
                    temp.delete()
                }
                VpnEventLog.record(context, "update",
                    "Ошибка; скачано ${temp.length() / 1048576} из ${size / 1048576} МиБ; " +
                        "тип: ${error.javaClass.simpleName}")
                throw error
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
