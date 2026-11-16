package xyz.wraithnet.phantom.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local bounded diagnostic history. Never stores share links, passwords,
 * subscription URLs, private keys, IP payloads or complete Xray configs.
 */
object VpnEventLog {
    private const val PREFS = "phantom_diagnostics"
    private const val KEY = "recent_events"
    private const val LIMIT = 40

    data class Entry(val timeMillis: Long, val state: String, val reason: String)

    @Synchronized
    fun record(context: Context, state: String, message: String = "") {
        val safeState = when (state) {
            "connected" -> "Подключено"
            "starting" -> "Подключение"
            "disconnected" -> "Отключено"
            "error" -> "Ошибка"
            else -> "Событие"
        }
        val safeReason = if (state == "error") scrub(message) else ""
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = runCatching {
            JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val array = JSONArray()
        array.put(JSONObject().put("timestamp", System.currentTimeMillis())
            .put("state", safeState).put("reason", safeReason))
        for (i in 0 until minOf(existing.length(), LIMIT - 1)) {
            array.put(existing.optJSONObject(i))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized
    fun read(context: Context): List<Entry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val json = JSONArray(raw)
        (0 until json.length()).mapNotNull { i ->
            json.optJSONObject(i)?.let {
                Entry(it.optLong("timestamp"), it.optString("state"), it.optString("reason"))
            }
        }
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun scrub(message: String): String {
        var text = message.take(400)
        text = text.replace(Regex("""(?i)\b(?:vless|vmess|trojan|hysteria2?|hy2|ss)://\S+"""), "[ссылка скрыта]")
        text = text.replace(Regex("""(?i)\b(?:https?|wss?)://\S+"""), "[адрес скрыт]")
        text = text.replace(Regex("""(?i)\b[A-F0-9]{8}(?:-[A-F0-9]{4}){3}-[A-F0-9]{12}\b"""), "[ID скрыт]")
        text = text.replace(Regex("""(?i)(?:password|passwd|secret|token|key)\s*[=:]\s*\S+"""), "[секрет скрыт]")
        return text.take(300)
    }
}
