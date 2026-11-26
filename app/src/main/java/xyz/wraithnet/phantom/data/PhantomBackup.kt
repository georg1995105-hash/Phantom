package xyz.wraithnet.phantom.data

import android.content.Context
import xyz.wraithnet.phantom.service.VpnRuntimeBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local, user-controlled backup. Contains subscription links, credentials and device ID.
 * No network transfer. Runtime VPN status and diagnostics are never restored.
 */
object PhantomBackup {
    const val MAX_SIZE = 5_000_000
    private const val FORMAT = "phantom-backup"
    private const val VERSION = 2
    private const val EXPANSION_PREFIX = "subscription_server_expanded_v1:"

    private val textKeys = setOf(
        "theme", "selected_subscription_id", "selected_server_id", "pinned_sub_id",
        "fragment_lengths", "fragment_delays", "app_route_mode",
        VpnDnsSettings.MODE_KEY, VpnDnsSettings.PRIMARY_KEY, VpnDnsSettings.SECONDARY_KEY
    )
    private val booleanKeys = setOf(
        "keep_active", "fragmentation_enabled", "connect_on_boot", PhantomHttp.DNS_FALLBACK_KEY,
        "auto_apk_updates", PhantomUpdates.BETA_UPDATES_PREF, "auto_tuic_ping", "haptic_feedback"
    )
    private const val TIMEOUT_KEY = "ping_timeout_seconds"
    private val setKeys = listOf("app_route_packages", "favorite_servers")

    data class Snapshot(
        val subscriptions: JSONArray,
        val settings: JSONObject,
        val deviceId: String?,
        val version: Int = VERSION,
    ) {
        val count: Int get() = subscriptions.length()
        val serverCount: Int get() = (0 until count).sumOf {
            subscriptions.getJSONObject(it).getJSONArray("servers").length()
        }
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
            require(servers.length() > 0) { "В архиве есть подписка без серверов" }
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
        if (settings.has(TIMEOUT_KEY)) {
            require(settings.opt(TIMEOUT_KEY) is Int && settings.getInt(TIMEOUT_KEY) in 2..12) {
                "Некорректный тайм-аут пинга"
            }
        }
        for (key in settings.keys().asSequence().filter { it.startsWith(EXPANSION_PREFIX) }) {
            require(key.length <= 512 && settings.opt(key) is Boolean) { "Некорректное состояние списка серверов" }
        }
        for (key in setKeys.filter(settings::has)) {
            val array = settings.optJSONArray(key)
                ?: throw IllegalArgumentException("Неверный список в настройках")
            require(array.length() <= 5000 && (0 until array.length()).all {
                array.opt(it) is String
            }) { "Повреждённый список в настройках" }
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
                root.optInt("version") in 1..VERSION) {
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
        return Snapshot(subscriptions, settings, deviceId, root.getInt("version"))
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
        for (key in setKeys) {
            settingsPrefs.getStringSet(key, null)?.let { settings.put(key, JSONArray(it.sorted())) }
        }
        (all[TIMEOUT_KEY] as? Int)?.let { settings.put(TIMEOUT_KEY, it) }
        all.forEach { (key, value) ->
            if (key.startsWith(EXPANSION_PREFIX) && value is Boolean) settings.put(key, value)
        }
        val (subscriptions, deviceId) = SubscriptionStore(context).backupSnapshot()
        return encode(subscriptions, settings, deviceId)
    }

    /**
     * Explicit user-approved restore. Does not reconnect a VPN or delete unrelated subscriptions.
     * If a preferences commit fails, the previous subscription state is put back.
     */
    fun restore(context: Context, snapshot: Snapshot): Int {
        val settings = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val runtime = VpnRuntimeBridge.snapshot(context)
        require(runtime.available && !runtime.running) {
            "Отключите VPN перед восстановлением"
        }
        verifySubscriptions(snapshot.subscriptions)
        verifySettings(snapshot.settings)
        val oldSettings = settings.all
        val knownKeys = textKeys + booleanKeys + setKeys + TIMEOUT_KEY
        val keys = knownKeys + oldSettings.keys.filter { it.startsWith(EXPANSION_PREFIX) } +
            snapshot.settings.keys().asSequence().filter { it.startsWith(EXPANSION_PREFIX) }.toList()
        fun restorePreviousSettings() {
            val rollback = settings.edit()
            for (key in keys) {
                when (val value = oldSettings[key]) {
                    is String -> rollback.putString(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
                    null -> rollback.remove(key)
                }
            }
            check(rollback.commit()) { "Не удалось откатить настройки" }
        }
        val restored = SubscriptionStore(context).restoreBackup(snapshot.subscriptions, snapshot.deviceId) {
            val edit = settings.edit()
            // v2 captures all supported preferences; missing values mean defaults.
            // v1 lacked some keys: preserve current values for backward compatibility.
            if (snapshot.version >= 2) {
                for (key in knownKeys) if (!snapshot.settings.has(key)) edit.remove(key)
                // Expansion belongs to each subscription. Preserve unrelated local groups.
                for (i in 0 until snapshot.count) {
                    val key = EXPANSION_PREFIX + snapshot.subscriptions.getJSONObject(i).getString("id")
                    if (!snapshot.settings.has(key)) edit.remove(key)
                }
            }
            for (key in textKeys) {
                if (snapshot.settings.has(key)) edit.putString(key, snapshot.settings.getString(key))
            }
            for (key in booleanKeys) {
                if (snapshot.settings.has(key)) edit.putBoolean(key, snapshot.settings.getBoolean(key))
            }
            for (key in setKeys.filter(snapshot.settings::has)) {
                val list = snapshot.settings.getJSONArray(key)
                edit.putStringSet(key, (0 until list.length()).map { list.getString(it) }.toSet())
            }
            if (snapshot.settings.has(TIMEOUT_KEY)) edit.putInt(TIMEOUT_KEY, snapshot.settings.getInt(TIMEOUT_KEY))
            for (key in snapshot.settings.keys().asSequence().filter { it.startsWith(EXPANSION_PREFIX) }) {
                edit.putBoolean(key, snapshot.settings.getBoolean(key))
            }
            try {
                check(edit.commit()) { "Не удалось сохранить настройки" }
            } catch (failure: Exception) {
                runCatching { restorePreviousSettings() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        return restored.size
    }
}
