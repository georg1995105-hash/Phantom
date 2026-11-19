package xyz.wraithnet.phantom.data

import java.net.URI
import java.util.Locale

/** Classify a copied link before attempting a one-tap import or any network access. */
object SubscriptionQuickImport {
    private val shareSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2", "tuic", "anytls"
    )

    fun isSubscriptionLink(value: String): Boolean {
        val link = value.trim()
        if (link.isBlank() || link.length > 16_384 ||
            link.any { it == '\n' || it == '\r' || it.isWhitespace() }) return false
        val scheme = link.substringBefore("://", "").lowercase(Locale.ROOT)
        if (scheme in shareSchemes) return link.substringAfter("://").isNotBlank()
        if (scheme !in setOf("http", "https")) return false
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        return !uri.host.isNullOrBlank()
    }
}
