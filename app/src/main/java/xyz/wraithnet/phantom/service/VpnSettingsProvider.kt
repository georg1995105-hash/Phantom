package xyz.wraithnet.phantom.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import xyz.wraithnet.phantom.data.AppRouting
import xyz.wraithnet.phantom.data.FragmentationOptions
import xyz.wraithnet.phantom.data.VpnDnsSettings

/**
 * One-shot settings snapshot from the default app process.
 *
 * Android SharedPreferences caches are not a reliable cross-process protocol.
 * The VPN must request current UI settings through Binder on every engine start
 * and on keep-awake changes, rather than reading stale copies in :vpn.
 */
internal object VpnSettingsBridge {
    data class Snapshot(
        val dns: VpnDnsSettings,
        val fragmentation: FragmentationOptions,
        val routeMode: String,
        val applications: Set<String>,
        val keepActive: Boolean,
        val uiVisible: Boolean,
    )

    private fun uri(context: Context) = Uri.parse("content://${context.packageName}.vpn-settings")

    data class Persisted(val requested: Boolean, val lastProfile: String)

    /** The default app process owns all durable preferences; :vpn never writes them. */
    fun persisted(context: Context): Persisted {
        val result = requireNotNull(
            context.contentResolver.call(uri(context), "persisted", null, null)
        ) { "Состояние VPN недоступно" }
        return Persisted(result.getBoolean("requested", false),
            result.getString("lastProfile").orEmpty())
    }

    fun persist(context: Context, requested: Boolean? = null, lastProfile: String? = null,
        running: Boolean? = null, status: String? = null, since: Long? = null): Boolean {
        val fields = Bundle().apply {
            requested?.let { putBoolean("requested", it) }
            lastProfile?.let { putString("lastProfile", it) }
            running?.let { putBoolean("running", it) }
            status?.let { putString("status", it) }
            since?.let { putLong("since", it) }
        }
        return context.contentResolver.call(uri(context), "persist", null, fields)
            ?.getBoolean("saved", false) == true
    }

    fun read(context: Context): Snapshot {
        val b = requireNotNull(context.contentResolver.call(uri(context), "snapshot", null, null)) {
            "Не удалось получить настройки VPN из процесса приложения"
        }
        val mode = requireNotNull(b.getString("dnsMode"))
        val dns = VpnDnsSettings(
            mode = VpnDnsSettings.Mode.valueOf(mode),
            customPrimary = b.getString("dnsPrimary").orEmpty(),
            customSecondary = b.getString("dnsSecondary").orEmpty(),
        )
        val fragment = FragmentationOptions(
            enabled = b.getBoolean("fragmentEnabled", false),
            lengths = b.getString("fragmentLengths").orEmpty(),
            delays = b.getString("fragmentDelays").orEmpty(),
        )
        val routingMode = b.getString("routeMode").orEmpty()
        require(routingMode in setOf(AppRouting.ALL, AppRouting.ONLY, AppRouting.EXCLUDE)) {
            "Недопустимые настройки маршрутизации приложений"
        }
        return Snapshot(dns, fragment, routingMode,
            b.getStringArrayList("applications").orEmpty().toSet(),
            b.getBoolean("keepActive", false), b.getBoolean("uiVisible", false))
    }
}

class VpnSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = requireNotNull(context)
        if (method == "persisted") {
            val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            return Bundle().apply {
                putBoolean("requested", prefs.getBoolean("vpn_requested", false))
                putString("lastProfile", prefs.getString("vpn_last_profile", "").orEmpty())
            }
        }
        if (method == "persist") {
            val changes = extras ?: return Bundle().apply { putBoolean("saved", false) }
            val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (changes.containsKey("requested"))
                editor.putBoolean("vpn_requested", changes.getBoolean("requested"))
            if (changes.containsKey("lastProfile"))
                editor.putString("vpn_last_profile", changes.getString("lastProfile"))
            if (changes.containsKey("running"))
                editor.putBoolean("vpn_running", changes.getBoolean("running"))
            if (changes.containsKey("status"))
                editor.putString("vpn_status", changes.getString("status"))
            if (changes.containsKey("since"))
                editor.putLong("vpn_connected_since_elapsed", changes.getLong("since"))
            return Bundle().apply { putBoolean("saved", editor.commit()) }
        }
        if (method != "snapshot") return null
        val dns = VpnDnsSettings.load(context)
        val fragment = FragmentationOptions.load(context)
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        return Bundle().apply {
            putString("dnsMode", dns.mode.name)
            putString("dnsPrimary", dns.customPrimary)
            putString("dnsSecondary", dns.customSecondary)
            putBoolean("fragmentEnabled", fragment.enabled)
            putString("fragmentLengths", fragment.lengths)
            putString("fragmentDelays", fragment.delays)
            putString("routeMode", AppRouting.mode(context))
            putStringArrayList("applications", ArrayList(AppRouting.packages(context)))
            putBoolean("keepActive", prefs.getBoolean("keep_active", false))
            putBoolean("uiVisible", prefs.getBoolean("ui_visible", false))
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?): Int = 0
}
