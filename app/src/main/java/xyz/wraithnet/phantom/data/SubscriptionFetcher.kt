package xyz.wraithnet.phantom.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class DownloadedSubscription(
    val body: String,
    val title: String? = null,
    val updateIntervalSeconds: Long? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAt: Long? = null,
)

object SubscriptionFetcher {
    suspend fun download(url: String, hwid: String): DownloadedSubscription = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Happ/1.0")
            setRequestProperty("x-hwid", hwid)
            setRequestProperty("x-device-os", "Android")
            setRequestProperty("x-device-model", Build.MODEL)
            setRequestProperty("x-ver-os", Build.VERSION.RELEASE ?: "unknown")
            setRequestProperty("Accept", "text/plain, application/json, */*")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Сервер подписки вернул HTTP $code" }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(body.isNotBlank()) { "Сервер вернул пустую подписку" }
            DownloadedSubscription(
                body = body,
                title = decodeHeader(connection.getHeaderField("profile-title")),
                updateIntervalSeconds = connection.getHeaderField("profile-update-interval")?.toLongOrNull(),
                uploadBytes = parseUserInfo(connection.getHeaderField("subscription-userinfo"), "upload"),
                downloadBytes = parseUserInfo(connection.getHeaderField("subscription-userinfo"), "download"),
                totalBytes = parseUserInfo(connection.getHeaderField("subscription-userinfo"), "total"),
                expiresAt = parseUserInfo(connection.getHeaderField("subscription-userinfo"), "expire"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeHeader(value: String?): String? = value?.trim()?.let { raw ->
        if (raw.isBlank()) return@let null
        val payload = raw.removePrefix("base64:").removePrefix("BASE64:")
        if (payload == raw && !raw.matches(Regex("[A-Za-z0-9+/=_-]+"))) return@let raw
        runCatching {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                .toString(Charsets.UTF_8)
        }.getOrDefault(raw).takeIf { it.isNotBlank() }
    }

    private fun parseUserInfo(value: String?, key: String): Long? = value
        ?.split(';')
        ?.firstOrNull { it.trim().startsWith("$key=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()
}
