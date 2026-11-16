package xyz.wraithnet.phantom.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/** Opt-in boot resume. Never requests VPN permission or steals an active VPN. */
class PhantomBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val profile = prefs.getString("vpn_last_profile", null)
        if (!VpnBootPolicy.mayStart(
                enabled = prefs.getBoolean(VpnBootPolicy.PREF_KEY, false),
                hasSavedProfile = !profile.isNullOrBlank(),
                authorized = VpnService.prepare(context) == null,
            )) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhantomVpnService::class.java)
                    .setAction(PhantomVpnService.ACTION_BOOT_START)
            )
        }
        // Vendor power restrictions can refuse background starts. Android's
        // separate Always-on VPN setting is the reliable system-managed option.
    }
}

object VpnBootPolicy {
    const val PREF_KEY = "connect_on_boot"

    fun mayStart(enabled: Boolean, hasSavedProfile: Boolean, authorized: Boolean): Boolean =
        enabled && hasSavedProfile && authorized
}
