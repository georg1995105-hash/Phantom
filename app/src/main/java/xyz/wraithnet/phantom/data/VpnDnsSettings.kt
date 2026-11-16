package xyz.wraithnet.phantom.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * DNS for traffic inside Phantom's TUN, distinct from the app's HTTPS bootstrap
 * resolver. Defaults preserve existing 0.2.11 behavior.
 */
data class VpnDnsSettings(
    val mode: Mode = Mode.DEFAULT,
    val customPrimary: String = "",
    val customSecondary: String = "",
) {
    enum class Mode { DEFAULT, CLOUDFLARE, GOOGLE, CUSTOM }

    fun addresses(): List<String> = when (mode) {
        Mode.DEFAULT -> listOf("1.1.1.1", "8.8.8.8")
        Mode.CLOUDFLARE -> listOf("1.1.1.1", "1.0.0.1")
        Mode.GOOGLE -> listOf("8.8.8.8", "8.8.4.4")
        Mode.CUSTOM -> listOf(customPrimary.trim(), customSecondary.trim())
            .filter { it.isNotBlank() }.distinct()
    }

    fun validate(): String? {
        if (mode != Mode.CUSTOM) return null
        if (!isIpLiteral(customPrimary.trim())) return "Укажите правильный IP основного DNS"
        if (customSecondary.isNotBlank() && !isIpLiteral(customSecondary.trim())) {
            return "Проверьте IP запасного DNS"
        }
        return null
    }

    /**
     * Custom provider Xray JSON is preserved in the default mode, but an explicit
     * user choice overrides its DNS servers. No profile or subscription is edited.
     */
    fun applyToXray(json: String): String {
        if (mode == Mode.DEFAULT) return json
        check(validate() == null) { validate() ?: "Неверный DNS" }
        val root = JSONObject(json)
        val dns = root.optJSONObject("dns") ?: JSONObject()
        dns.put("servers", JSONArray(addresses()))
        root.put("dns", dns)
        return root.toString()
    }

    companion object {
        private const val PREFS = "phantom_preferences"
        const val MODE_KEY = "vpn_dns_mode"
        const val PRIMARY_KEY = "vpn_dns_custom_primary"
        const val SECONDARY_KEY = "vpn_dns_custom_secondary"

        fun isIpLiteral(address: String): Boolean {
            val parts = address.split('.')
            if (parts.size == 4) {
                return parts.all { piece ->
                    piece.isNotEmpty() && piece.length <= 3 &&
                        piece.all(Char::isDigit) &&
                        (piece.length == 1 || !piece.startsWith("0")) &&
                        (piece.toIntOrNull() ?: 256) in 0..255
                }
            }
            // IPv6 literals: no hostnames, zone identifiers or DNS lookup.
            return address.contains(':') && !address.contains('%') &&
                address.count { it == ':' } >= 2 &&
                address.all { it == ':' || it == '.' ||
                    it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } &&
                runCatching { java.net.InetAddress.getByName(address).address.size == 16 }
                    .getOrDefault(false)
        }

        fun load(context: Context): VpnDnsSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return VpnDnsSettings(
                mode = runCatching {
                    Mode.valueOf(p.getString(MODE_KEY, Mode.DEFAULT.name) ?: Mode.DEFAULT.name)
                }.getOrDefault(Mode.DEFAULT),
                customPrimary = p.getString(PRIMARY_KEY, "") ?: "",
                customSecondary = p.getString(SECONDARY_KEY, "") ?: "",
            )
        }

        fun save(context: Context, value: VpnDnsSettings): Boolean {
            require(value.validate() == null) { value.validate() ?: "Неверный DNS" }
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(MODE_KEY, value.mode.name)
                .putString(PRIMARY_KEY, value.customPrimary.trim())
                .putString(SECONDARY_KEY, value.customSecondary.trim())
                .commit()
        }
    }
}
