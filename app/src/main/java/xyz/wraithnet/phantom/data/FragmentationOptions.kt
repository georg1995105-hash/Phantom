package xyz.wraithnet.phantom.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Optional Xray FinalMask TLS ClientHello fragmentation, disabled by default. */
data class FragmentationOptions(
    val enabled: Boolean = false,
    val lengths: String = "80-120",
    val delays: String = "3-10",
) {
    companion object {
        private const val PREFS = "phantom_preferences"

        fun load(context: Context): FragmentationOptions {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return FragmentationOptions(
                p.getBoolean("fragmentation_enabled", false),
                p.getString("fragment_lengths", "80-120") ?: "80-120",
                p.getString("fragment_delays", "3-10") ?: "3-10"
            )
        }

        fun save(context: Context, value: FragmentationOptions) {
            require(value.isValid()) { "Неверные параметры фрагментации" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("fragmentation_enabled", value.enabled)
                .putString("fragment_lengths", value.lengths)
                .putString("fragment_delays", value.delays)
                .apply()
        }
    }

    private fun parse(input: String, minAllowed: Int, maxAllowed: Int): Boolean {
        val m = Regex("""^(\d{1,4})(?:-(\d{1,4}))?$""").matchEntire(input.trim())
            ?: return false
        val a = m.groupValues[1].toIntOrNull() ?: return false
        val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: a
        return a in minAllowed..maxAllowed && b in minAllowed..maxAllowed && b >= a
    }

    fun isValid(): Boolean =
        parse(lengths, 20, 500) && parse(delays, 0, 100)

    /** Does not modify UDP-based Hysteria2 or existing user-provided TCP finalmasks. */
    fun apply(config: String): String {
        if (!enabled) return config
        require(isValid()) { "Неверные параметры фрагментации" }
        val root = JSONObject(config)
        val outbounds = root.optJSONArray("outbounds") ?: return config
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val protocol = outbound.optString("protocol").lowercase()
            if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks")) continue
            val stream = outbound.optJSONObject("streamSettings") ?: continue
            if (stream.optString("network") == "hysteria") continue
            if (stream.optString("security") !in setOf("tls", "reality")) continue
            val finalmask = stream.optJSONObject("finalmask") ?: JSONObject()
            val tcp = finalmask.optJSONArray("tcp") ?: JSONArray()
            val already = (0 until tcp.length()).any { tcp.optJSONObject(it)?.optString("type") == "fragment" }
            if (already) continue
            tcp.put(JSONObject().put("type", "fragment").put("settings", JSONObject()
                .put("packets", "tlshello")
                .put("lengths", JSONArray().put(lengths))
                .put("delays", JSONArray().put(delays))
                .put("maxSplit", "3-8")))
            finalmask.put("tcp", tcp)
            stream.put("finalmask", finalmask)
        }
        return root.toString()
    }
}
