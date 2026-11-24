package xyz.wraithnet.phantom.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Classify a copied link before attempting a one-tap import or any network access. */
object SubscriptionQuickImport {
    private val shareSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2", "tuic", "anytls"
    )

    /**
     * Browser contract: phantom://import?url=<encodeURIComponent(subscriptionUrl)>.
     * Decode exactly once. Do not interpret extra parameters as part of the
     * subscription, open arbitrary schemes, or disclose the URL in errors.
     */
    fun unwrap(value: String): String {
        val link = value.trim()
        if (!link.startsWith("phantom://import", ignoreCase = true)) return link
        val error = "Неверная ссылка добавления подписки"
        require(link.length <= 65_536) { error }
        val uri = runCatching { URI(link) }.getOrNull()
        require(uri != null && uri.scheme == "phantom" && uri.rawAuthority == "import" &&
            uri.rawPath.isNullOrEmpty() && uri.rawFragment == null) { error }
        val query = uri.rawQuery.orEmpty()
        require(query.startsWith("url=") && !query.contains('&') && !query.contains('+')) { error }
        val encoded = query.removePrefix("url=")
        require(encoded.isNotEmpty() && encoded.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-_.!~*'()%"
        }) { error }
        val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        require(decoded != null && decoded.length in 1..16_384 &&
            decoded.none { it.isWhitespace() || it.isISOControl() || it == '\uFFFD' }) { error }
        val target = runCatching { URI(decoded) }.getOrNull()
        require(target != null && target.scheme in setOf("https", "http") &&
            !target.host.isNullOrBlank() && target.rawUserInfo == null) { error }
        return decoded
    }

    fun isSubscriptionLink(value: String): Boolean {
        val link = runCatching { unwrap(value) }.getOrElse { return false }
        if (link.isBlank() || link.length > 16_384 ||
            link.any { it == '\n' || it == '\r' || it.isWhitespace() }) return false
        val scheme = link.substringBefore("://", "").lowercase(Locale.ROOT)
        if (scheme in shareSchemes) return link.substringAfter("://").isNotBlank()
        if (scheme !in setOf("http", "https")) return false
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        return !uri.host.isNullOrBlank()
    }
}
