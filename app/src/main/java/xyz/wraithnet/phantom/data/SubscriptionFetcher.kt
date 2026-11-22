package xyz.wraithnet.phantom.data

import android.content.Context
import android.os.Build
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import xyz.wraithnet.phantom.BuildConfig

data class DownloadedSubscription(
    val body: String,
    val title: String? = null,
    val updateIntervalSeconds: Long? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAt: Long? = null,
    val announcement: String? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
)

object SubscriptionFetcher {
    private const val PHANTOM_SUBSCRIPTIONS_HOST = "sub2.wraithnet.xyz"
    private val shortId = Regex("[A-Za-z0-9_-]{1,64}")

    private fun firstPartyPath(url: HttpUrl): Boolean =
        url.isHttps && url.host == PHANTOM_SUBSCRIPTIONS_HOST && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            shortId.matches(url.pathSegments.first()) &&
            (url.pathSegments.dropLastWhile(String::isEmpty).let {
                it.size == 1 || it.size == 2 && it[1] == "json"
            })

    internal fun usesProviderJson(url: String): Boolean =
        url.toHttpUrlOrNull()?.let(::firstPartyPath) == true

    internal fun requestUrl(source: String): String {
        val url = source.toHttpUrl()
        if (!firstPartyPath(url)) return source
        // Remnawave's explicit format route delivers the provider's Xray
        // templates and keeps Phantom's own User-Agent and device identity.
        return url.newBuilder().encodedPath("/${url.pathSegments.first()}/json").build().toString()
    }

    internal suspend fun download(
        context: Context,
        url: String,
        hwid: String,
        previous: Subscription? = null,
        vpnRoute: ActiveVpnSubscriptionProxy.Route? = null,
    ): DownloadedSubscription = PhantomHttp.get(
        context = context,
        url = requestUrl(url),
        headers = mapOf(
            "User-Agent" to "Phantom/${BuildConfig.VERSION_NAME}",
            "x-hwid" to hwid,
            "x-device-os" to "Android",
            "x-device-model" to Build.MODEL,
            "x-ver-os" to (Build.VERSION.RELEASE ?: "unknown"),
            "Accept" to "text/plain, application/json, */*",
        ),
        callTimeoutMs = 60_000L,
        vpnRoute = vpnRoute,
    ) { response ->
        val bytes = response.body?.byteStream() ?: error("Пустой ответ сервера подписки")
        val body = bytes.use { PhantomHttp.readUtf8Limited(it, 3 * 1024 * 1024) }
        require(body.isNotBlank()) { "Сервер вернул пустую подписку" }
        if (usesProviderJson(url)) {
            // An error page or a list of share links must not replace the
            // saved JSON subscription and silently discard routing.
            val parsed = runCatching { JSONArray(body.trimStart().removePrefix("\uFEFF")) }.getOrNull()
            require(parsed != null && parsed.length() > 0 && (0 until parsed.length()).all {
                (parsed.optJSONObject(it)?.optJSONArray("outbounds")?.length() ?: 0) > 0
            }) { "Сервер не вернул профили Xray JSON" }
        }
        DownloadedSubscription(
            body = body,
            title = SubscriptionHeaders.decodeTitle(response.header("profile-title")),
            updateIntervalSeconds = response.header("profile-update-interval")
                ?.toLongOrNull()?.times(3600),
            uploadBytes = parseUserInfo(response.header("subscription-userinfo"), "upload"),
            downloadBytes = parseUserInfo(response.header("subscription-userinfo"), "download"),
            totalBytes = parseUserInfo(response.header("subscription-userinfo"), "total"),
            expiresAt = parseUserInfo(response.header("subscription-userinfo"), "expire") ?: previous?.expiresAt,
            announcement = if (response.header("announce") == null) previous?.announcement
                else SubscriptionHeaders.decodeAnnouncement(response.header("announce")),
            supportUrl = if (response.header("support-url") == null) previous?.supportUrl
                else SubscriptionHeaders.safeHttpUrl(response.header("support-url")),
            webPageUrl = if (response.header("profile-web-page-url") == null) previous?.webPageUrl
                else SubscriptionHeaders.safeHttpUrl(response.header("profile-web-page-url")),
        )
    }

    private fun parseUserInfo(value: String?, key: String): Long? = value
        ?.split(';')
        ?.firstOrNull { it.trim().startsWith("$key=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()
}
