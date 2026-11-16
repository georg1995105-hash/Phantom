package xyz.wraithnet.phantom.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("phantom_data", Context.MODE_PRIVATE)

    fun deviceId(): String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }

    fun read(): List<Subscription> = runCatching {
        val array = JSONArray(prefs.getString("subscriptions", "[]"))
        buildList {
            for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
        }
    }.getOrDefault(emptyList())

    fun write(items: List<Subscription>) {
        prefs.edit().putString("subscriptions", JSONArray(items.map(::toJson)).toString()).apply()
    }

    private fun toJson(item: Subscription) = JSONObject().apply {
        put("id", item.id); put("title", item.title); put("source", item.source)
        put("updatedAt", item.updatedAt); put("interval", item.updateIntervalSeconds)
        item.uploadBytes?.let { put("uploadBytes", it) }
        item.downloadBytes?.let { put("downloadBytes", it) }
        item.totalBytes?.let { put("totalBytes", it) }
        item.expiresAt?.let { put("expiresAt", it) }
        put("servers", JSONArray(item.servers.map { server -> JSONObject().apply {
            put("id", server.id); put("name", server.name); put("scheme", server.scheme)
            put("address", server.address); put("port", server.port ?: JSONObject.NULL)
            put("raw", server.raw); put("flag", server.countryFlag)
        } }))
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun fromJson(item: JSONObject): Subscription = Subscription(
        id = item.getString("id"), title = item.getString("title"), source = item.getString("source"),
        updatedAt = item.optLong("updatedAt"), updateIntervalSeconds = item.optLong("interval", 3600),
        uploadBytes = item.optLongOrNull("uploadBytes"), downloadBytes = item.optLongOrNull("downloadBytes"),
        totalBytes = item.optLongOrNull("totalBytes"), expiresAt = item.optLongOrNull("expiresAt"),
        servers = buildList {
            val array = item.optJSONArray("servers") ?: JSONArray()
            for (i in 0 until array.length()) {
                val server = array.getJSONObject(i)
                add(ServerProfile(server.getString("id"), server.getString("name"), server.getString("scheme"),
                    server.optString("address"), server.optInt("port").takeIf { it > 0 }, server.getString("raw"), server.optString("flag")))
            }
        }
    )
}
