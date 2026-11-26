package xyz.wraithnet.phantom.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("phantom_data", Context.MODE_PRIVATE)

    companion object {
        // The UI and WorkManager run in the app process. A shared process-wide lock
        // protects their read-modify-write operations and durable rollback snapshot.
        private val lock = Any()
        private const val CURRENT = "subscriptions"
        private const val PREVIOUS = "subscriptions_previous"
    }

    fun deviceId(): String = synchronized(lock) {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("device_id", it).commit()) {
                "Не удалось сохранить ID устройства"
            }
        }
    }

    private fun decode(json: String): List<Subscription> {
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
        }
    }

    /** Do not silently turn malformed saved subscriptions into an empty list. */
    private fun readLocked(): List<Subscription> {
        val current = prefs.getString(CURRENT, null) ?: return emptyList()
        return try {
            decode(current)
        } catch (failure: Exception) {
            val previous = prefs.getString(PREVIOUS, null)
            if (previous != null) {
                val recovered = runCatching { decode(previous) }.getOrNull()
                if (recovered != null) {
                    check(prefs.edit().putString(CURRENT, previous).commit()) {
                        "Не удалось восстановить предыдущие подписки"
                    }
                    return recovered
                }
            }
            throw IllegalStateException(
                "Хранилище подписок повреждено. Данные не удалены; требуется восстановление",
                failure
            )
        }
    }

    fun read(): List<Subscription> = synchronized(lock) { readLocked() }

    /** Use the same serializer and recovery path as the live subscription list. */
    internal fun backupSnapshot(): Pair<String, String?> = synchronized(lock) {
        JSONArray(readLocked().map(::toJson)).toString() to prefs.getString("device_id", null)
    }

    /** Serialize against UI/Worker writes; restore all stored data on a failed settings commit. */
    internal fun restoreBackup(incoming: JSONArray, deviceId: String?,
        saveSettings: () -> Unit): List<Subscription> = synchronized(lock) {
        val current = readLocked()
        val oldJson = prefs.getString(CURRENT, null)
        val oldPrevious = prefs.getString(PREVIOUS, null)
        val oldDevice = prefs.getString("device_id", null)
        val merged = PhantomBackup.merge(JSONArray(current.map(::toJson)), incoming)
        val decoded = decode(merged.toString())
        val editor = prefs.edit().putString(CURRENT, merged.toString())
        if (oldJson != null) editor.putString(PREVIOUS, oldJson)
        deviceId?.let { editor.putString("device_id", it) }
        try {
            check(editor.commit()) { "Не удалось сохранить подписки" }
            saveSettings()
        } catch (failure: Exception) {
            val rollback = prefs.edit()
            for ((key, value) in listOf(CURRENT to oldJson, PREVIOUS to oldPrevious, "device_id" to oldDevice)) {
                if (value == null) rollback.remove(key) else rollback.putString(key, value)
            }
            if (!rollback.commit()) failure.addSuppressed(IllegalStateException("Не удалось откатить подписки"))
            throw failure
        }
        decoded
    }

    fun observeChanges(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CURRENT) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun writeLocked(items: List<Subscription>) {
        val json = JSONArray(items.map(::toJson)).toString()
        val old = prefs.getString(CURRENT, null)
        if (old == json) return
        // Check validity before a backup can overwrite the last good snapshot.
        val editor = prefs.edit().putString(CURRENT, json)
        if (old != null && runCatching { decode(old) }.isSuccess) {
            editor.putString(PREVIOUS, old)
        }
        check(editor.commit()) { "Не удалось сохранить подписки" }
    }

    fun write(items: List<Subscription>) = synchronized(lock) {
        readLocked() // Fail closed on unrecoverable corruption, never erase keys.
        writeLocked(items)
    }

    /** Atomic UI/Worker mutation; always transform the newest saved snapshot. */
    fun update(transform: (List<Subscription>) -> List<Subscription>): List<Subscription> =
        synchronized(lock) {
            val updated = transform(readLocked())
            writeLocked(updated)
            updated
        }

    private fun toJson(item: Subscription) = JSONObject().apply {
        put("id", item.id); put("title", item.title); put("source", item.source)
        put("updatedAt", item.updatedAt); put("interval", item.updateIntervalSeconds)
        item.uploadBytes?.let { put("uploadBytes", it) }
        item.downloadBytes?.let { put("downloadBytes", it) }
        item.totalBytes?.let { put("totalBytes", it) }
        item.expiresAt?.let { put("expiresAt", it) }
        item.customTitle?.let { put("customTitle", it) }
        item.customIntervalSeconds?.let { put("customIntervalSeconds", it) }
        item.selectedServerId?.let { put("selectedServerId", it) }
        item.announcement?.let { put("announcement", it) }
        item.supportUrl?.let { put("supportUrl", it) }
        item.webPageUrl?.let { put("webPageUrl", it) }
        item.accountTitle?.let { put("accountTitle", it) }
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
        customTitle = item.optString("customTitle").takeIf { it.isNotBlank() },
        customIntervalSeconds = item.optLongOrNull("customIntervalSeconds"),
        selectedServerId = item.optString("selectedServerId").takeIf { it.isNotBlank() },
        announcement = item.optString("announcement").takeIf { it.isNotBlank() },
        supportUrl = item.optString("supportUrl").takeIf { it.isNotBlank() },
        webPageUrl = item.optString("webPageUrl").takeIf { it.isNotBlank() },
        accountTitle = item.optString("accountTitle").takeIf { it.isNotBlank() },
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
