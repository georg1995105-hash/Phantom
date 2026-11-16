package xyz.wraithnet.phantom.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local, user-controlled backup. Contains subscription links, credentials and device ID.
 * No network transfer. Runtime VPN status and diagnostics are never restored.
 */
object PhantomBackup {
    const val MAX_SIZE = 5_000_000
    private const val FORMAT = "phantom-backup"
    private const val VERSION = 1

    private val textKeys = setOf(
        "theme", "selected_server_id", "pinned_sub_id",
        "fragment_lengths", "fragment_delays", "app_route_mode",
        VpnDnsSettings.MODE_KEY, VpnDnsSettings.PRIMARY_KEY, VpnDnsSettings.SECONDARY_KEY
    )
    private val booleanKeys = setOf(
        "keep_active", "fragmentation_enabled", "connect_on_boot", PhantomHttp.DNS_FALLBACK_KEY
    )
    private const val PACKAGE_KEY = "app_route_packages"

    data class Snapshot(
        val subscriptions: JSONArray,
        val settings: JSONObject,
        val deviceId: String?,
    ) {
        val count: Int get() = subscriptions.length()
    }

    private fun verifySubscriptions(array: JSONArray) {
        require(array.length() <= 200) { "Слишком много подписок в архиве" }
        var count = 0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: throw IllegalArgumentException("Повреждённая подписка в архиве")
            require(item.optString("id").isNotBlank() &&
                    item.optString("title").isNotBlank() &&
                    item.has("source") && item.opt("source") is String) {
                "В подписке отсутствуют необходимые поля"
            }
            val servers = item.optJSONArray("servers")
                ?: throw IllegalArgumentException("У подписки нет списка серверов")
            count += servers.length()
            require(count <= 5000) { "Слишком много серверов в архиве" }
            for (j in 0 until servers.length()) {
                val server = servers.optJSONObject(j)
                    ?: throw IllegalArgumentException("Повреждённая запись сервера")
                require(listOf("id", "name", "scheme", "raw").all {
                    server.optString(it).isNotBlank()
                }) { "В архиве неполная конфигурация сервера" }
            }
        }
    }

    private fun verifySettings(settings: JSONObject) {
        for (key in textKeys) {
            if (settings.has(key)) {
                require(settings.opt(key) is String) { "Некорректная настройка: $key" }
            }
        }
        for (key in booleanKeys) {
            if (settings.has(key)) {
                require(settings.opt(key) is Boolean) { "Некорректная настройка: $key" }
            }
        }
        if (settings.has(PACKAGE_KEY)) {
            val array = settings.optJSONArray(PACKAGE_KEY)
                ?: throw IllegalArgumentException("Неверный список приложений")
            require(array.length() <= 5000 && (0 until array.length()).all {
                array.opt(it) is String
            }) { "Повреждённый список приложений" }
        }
        if (settings.has("theme")) {
            require(settings.getString("theme") in setOf("AUTO","LIGHT","GRAPHITE","NIGHT","FOREST")) {
                "Неизвестная тема оформления"
            }
        }
        if (settings.has(VpnDnsSettings.MODE_KEY)) {
            require(settings.getString(VpnDnsSettings.MODE_KEY) in
                VpnDnsSettings.Mode.entries.map { it.name }) {
                "Неизвестный режим DNS"
            }
        }
        if (settings.has("app_route_mode")) {
            require(settings.getString("app_route_mode") in
                setOf(AppRouting.ALL, AppRouting.ONLY, AppRouting.EXCLUDE)) {
                "Неизвестный режим VPN для приложений"
            }
        }
    }

    fun encode(subscriptionsJson: String, settings: JSONObject, deviceId: String?): String {
        val subscriptions = JSONArray(subscriptionsJson)
        verifySubscriptions(subscriptions)
        verifySettings(settings)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("subscriptions", subscriptions)
            .put("settings", settings)
            .apply { if (!deviceId.isNullOrBlank()) put("deviceId", deviceId) }
            .toString(2).also { require(it.length <= MAX_SIZE) { "Архив слишком большой" } }
    }

    fun parse(text: String): Snapshot {
        require(text.isNotBlank() && text.length <= MAX_SIZE) {
            "Пустой или слишком большой файл резервной копии"
        }
        val root = JSONObject(text.trim().removePrefix("\uFEFF"))
        require(root.optString("format") == FORMAT &&
                root.optInt("version") == VERSION) {
            "Файл не является совместимой резервной копией Phantom"
        }
        val subscriptions = root.optJSONArray("subscriptions")
            ?: throw IllegalArgumentException("В архиве нет подписок")
        val settings = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("В архиве нет настроек")
        verifySubscriptions(subscriptions)
        verifySettings(settings)
        val deviceId = root.optString("deviceId").takeIf { it.isNotBlank() }
        require(deviceId == null || deviceId.length <= 128) { "Недопустимый ID устройства" }
        return Snapshot(subscriptions, settings, deviceId)
    }

    /** Replacing matching ID/source records while preserving other local subscriptions. */
    fun merge(existing: JSONArray, incoming: JSONArray): JSONArray {
        verifySubscriptions(existing)
        verifySubscriptions(incoming)
        val result = mutableListOf<JSONObject>()
        for (i in 0 until existing.length()) result.add(existing.getJSONObject(i))
        for (i in 0 until incoming.length()) {
            val next = incoming.getJSONObject(i)
            val id = next.getString("id")
            val source = next.getString("source").trim()
            val index = result.indexOfFirst {
                it.getString("id") == id ||
                    (source.isNotBlank() && it.getString("source").trim() == source)
            }
            if (index >= 0) result[index] = next else result.add(next)
        }
        return JSONArray(result).also { verifySubscriptions(it) }
    }

    fun capture(context: Context): String {
        val settingsPrefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val dataPrefs = context.getSharedPreferences("phantom_data", Context.MODE_PRIVATE)
        val settings = JSONObject()
        val all = settingsPrefs.all
        for (key in textKeys) {
            val value = all[key]
            if (value is String) settings.put(key, value)
        }
        for (key in booleanKeys) {
            val value = all[key]
            if (value is Boolean) settings.put(key, value)
        }
        @Suppress("UNCHECKED_CAST")
        val packages = all[PACKAGE_KEY] as? Set<String>
        if (packages != null) settings.put(PACKAGE_KEY, JSONArray(packages.sorted()))
        return encode(
            dataPrefs.getString("subscriptions", "[]") ?: "[]",
            settings,
            dataPrefs.getString("device_id", null)
        )
    }

    /**
     * Explicit user-approved restore. Does not reconnect a VPN or delete unrelated subscriptions.
     * If a preferences commit fails, the previous subscription state is put back.
     */
    fun restore(context: Context, snapshot: Snapshot): Int {
        val settings = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        require(!settings.getBoolean("vpn_running", false)) {
            "Отключите VPN перед восстановлением"
        }
        verifySubscriptions(snapshot.subscriptions)
        verifySettings(snapshot.settings)
        val data = context.getSharedPreferences("phantom_data", Context.MODE_PRIVATE)
        val previousJson = data.getString("subscriptions", "[]") ?: "[]"
        val previousDeviceId = data.getString("device_id", null)
        val merged = merge(JSONArray(previousJson), snapshot.subscriptions).toString()
        val dataEdit = data.edit().putString("subscriptions", merged)
        snapshot.deviceId?.let { dataEdit.putString("device_id", it) }
        check(dataEdit.commit()) { "Не удалось сохранить подписки" }
        try {
            val edit = settings.edit()
            for (key in textKeys) {
                if (snapshot.settings.has(key)) edit.putString(key, snapshot.settings.getString(key))
            }
            for (key in booleanKeys) {
                if (snapshot.settings.has(key)) edit.putBoolean(key, snapshot.settings.getBoolean(key))
            }
            if (snapshot.settings.has(PACKAGE_KEY)) {
                val list = snapshot.settings.getJSONArray(PACKAGE_KEY)
                edit.putStringSet(PACKAGE_KEY, (0 until list.length())
                    .map { list.getString(it) }.toSet())
            }
            check(edit.commit()) { "Не удалось сохранить настройки" }
        } catch (e: Exception) {
            val rollback = data.edit().putString("subscriptions", previousJson)
            if (previousDeviceId == null) rollback.remove("device_id")
            else rollback.putString("device_id", previousDeviceId)
            rollback.commit()
            throw e
        }
        return JSONArray(merged).length()
    }
}
