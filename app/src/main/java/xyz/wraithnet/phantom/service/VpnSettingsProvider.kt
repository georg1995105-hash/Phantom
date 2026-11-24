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
        if (method != "snapshot") return null
        val context = requireNotNull(context)
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
