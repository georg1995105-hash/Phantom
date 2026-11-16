package xyz.wraithnet.phantom.data

import android.content.Context
import android.os.Build

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
    suspend fun download(
        context: Context,
        url: String,
        hwid: String,
    ): DownloadedSubscription = PhantomHttp.get(
        context = context,
        url = url,
        headers = mapOf(
            "User-Agent" to "Happ/1.0",
            "x-hwid" to hwid,
            "x-device-os" to "Android",
            "x-device-model" to Build.MODEL,
            "x-ver-os" to (Build.VERSION.RELEASE ?: "unknown"),
            "Accept" to "text/plain, application/json, */*",
        ),
        callTimeoutMs = 60_000L,
    ) { response ->
        val bytes = response.body?.byteStream() ?: error("Пустой ответ сервера подписки")
        val body = bytes.use { PhantomHttp.readUtf8Limited(it, 3 * 1024 * 1024) }
        require(body.isNotBlank()) { "Сервер вернул пустую подписку" }
        DownloadedSubscription(
            body = body,
            title = SubscriptionHeaders.decodeTitle(response.header("profile-title")),
            updateIntervalSeconds = response.header("profile-update-interval")
                ?.toLongOrNull()?.times(3600),
            uploadBytes = parseUserInfo(response.header("subscription-userinfo"), "upload"),
            downloadBytes = parseUserInfo(response.header("subscription-userinfo"), "download"),
            totalBytes = parseUserInfo(response.header("subscription-userinfo"), "total"),
            expiresAt = parseUserInfo(response.header("subscription-userinfo"), "expire"),
        )
    }

    private fun parseUserInfo(value: String?, key: String): Long? = value
        ?.split(';')
        ?.firstOrNull { it.trim().startsWith("$key=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()
}
