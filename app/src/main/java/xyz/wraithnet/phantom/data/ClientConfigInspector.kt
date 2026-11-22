package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Local-only inspection of the client's per-node configuration. Never uploads a key or raw
 * profile. Xray uses the same production builder; TUIC/AnyTLS uses the sing-box builder's
 * outbound (the runtime-only SOCKS listener port is intentionally omitted).
 */
internal object ClientConfigInspector {
    const val MAX_PROFILE_CHARS = 500_000
    const val PREVIEW_CHARS = 50_000

    internal data class Document(val engine: String, val full: String, val masked: String)

    private val sensitive = setOf(
        "id", "uuid", "password", "passwd", "pass", "privatekey",
        "private_key", "secret", "token", "authorization", "proxyauthorization",
        "psk", "apikey", "api_key", "user", "username", "subscriptionurl",
        "subscription_url", "cookie"
    )

    fun inspect(profile: ServerProfile): Document {
        require(profile.raw.length <= MAX_PROFILE_CHARS) { "Слишком большая конфигурация" }
        val singBox = SingBoxBridgeConfig.requiresSingBox(profile)
        val json = if (singBox) {
            // Exactly the native outbound generated for this share link. No fake
            // runtime port or inbound is included in the exported document.
            JSONObject(SingBoxBridgeConfig.fromShareLink(profile.raw, 1080))
                .getJSONArray("outbounds").getJSONObject(0)
        } else {
            // For provider Xray JSON, preserve the provider's routes and outbounds.
            JSONObject(XrayConfigBuilder.build(profile))
        }
        val full = json.toString(2)
        require(full.length <= MAX_PROFILE_CHARS * 2) { "Слишком большая конфигурация" }
        val copy = JSONObject(full)
        mask(copy)
        return Document(if (singBox) "sing-box · outbound" else "Xray · JSON", full, copy.toString(2))
    }

    private fun mask(node: Any) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                keys.forEach { key ->
                    val value = node.opt(key)
                    val normalized = key.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
                    if (normalized in sensitive.map { it.replace("_", "") }) {
                        if (value != null && value != JSONObject.NULL) node.put(key, "••••••")
                    } else if (value is JSONObject || value is JSONArray) {
                        mask(value)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val value = node.opt(i)
                if (value is JSONObject || value is JSONArray) mask(value)
            }
        }
    }
}
