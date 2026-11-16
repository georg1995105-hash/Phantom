package xyz.wraithnet.phantom.data

/** Decodes the optional standard profile-title response header, plain or base64 encoded. */
object SubscriptionHeaders {
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
