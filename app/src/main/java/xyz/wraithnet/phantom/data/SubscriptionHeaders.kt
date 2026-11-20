package xyz.wraithnet.phantom.data

/** Decodes the optional standard profile-title response header, plain or base64 encoded. */
object SubscriptionHeaders {
    /** Provider announcement is optional and may be Base64 encoded. "0" removes it. */
    fun decodeAnnouncement(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() && it != "0" } ?: return null
        val decoded = if (raw.startsWith("base64:", ignoreCase = true))
            runCatching { ShareLinkCodec.base64Text(raw.substringAfter(':')) }.getOrNull() ?: return null
        else raw
        return decoded.trim().takeIf { it.isNotBlank() && it != "0" &&
            !it.contains('\uFFFD') && it.none { ch -> ch.code < 32 && ch != '\n' && ch != '\t' }
        }?.take(200)
    }

    /** Never forward credentials or script/file schemes from a provider into Intent.ACTION_VIEW. */
    fun safeHttpUrl(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotBlank() && it.length <= 2048 &&
            it.none { c -> c.isWhitespace() || c.code < 32 }
        } ?: return null
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("https", "http") ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return text
    }

    fun decodeTitle(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val prefixed = raw.startsWith("base64:", ignoreCase = true)
        val payload = if (prefixed) raw.substringAfter(':') else raw
        if (!prefixed && !payload.matches(Regex("[A-Za-z0-9+/=_-]{8,}"))) return raw
        return runCatching {
            ShareLinkCodec.base64Text(payload)
                .takeIf { it.isNotBlank() && !it.contains('\uFFFD') &&
                    it.none { ch -> ch.code < 32 && ch != '\n' && ch != '\t' } }
                ?: raw
        }.getOrDefault(raw)
    }
}
