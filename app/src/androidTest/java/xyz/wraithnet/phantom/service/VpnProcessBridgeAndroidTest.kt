package xyz.wraithnet.phantom.service

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnProcessBridgeAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun vpnAndSettingsProvidersHaveIsolatedPrivateProcesses() {
        val pm = context.packageManager
        val vpn = requireNotNull(pm.resolveContentProvider(
            "${context.packageName}.vpn-runtime", PackageManager.GET_META_DATA))
        val settings = requireNotNull(pm.resolveContentProvider(
            "${context.packageName}.vpn-settings", PackageManager.GET_META_DATA))
        assertFalse(vpn.exported)
        assertFalse(settings.exported)
        assertEquals("${context.packageName}:vpn", vpn.processName)
        assertEquals(context.packageName, settings.processName)
        val service = pm.getServiceInfo(android.content.ComponentName(
            context, PhantomVpnService::class.java), PackageManager.GET_META_DATA)
        assertEquals("${context.packageName}:vpn", service.processName)
    }

    @Test fun preferenceSnapshotReflectsCurrentMainProcessSettings() {
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val old = prefs.all.toMap()
        try {
            prefs.edit().putString("vpn_dns_mode", "GOOGLE")
                .putBoolean("fragmentation_enabled", true)
                .putString("fragment_lengths", "80-120")
                .putString("fragment_delays", "3-10")
                .putString("app_route_mode", "exclude")
                .putStringSet("app_route_packages", setOf("com.example.synthetic"))
                .putBoolean("keep_active", true)
                .commit()
            val snapshot = VpnSettingsBridge.read(context)
            assertEquals("GOOGLE", snapshot.dns.mode.name)
            assertTrue(snapshot.fragmentation.enabled)
            assertEquals("exclude", snapshot.routeMode)
            assertEquals(setOf("com.example.synthetic"), snapshot.applications)
            assertTrue(snapshot.keepActive)
        } finally {
            val editor = prefs.edit().clear()
            for ((key, value) in old) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            editor.commit()
        }
    }

    @Test fun unknownProfileCannotObtainProxyPassword() {
        val randomDigest = "0".repeat(64)
        assertNull(VpnRuntimeBridge.routeForDigest(context, randomDigest))
        val current = VpnRuntimeBridge.snapshot(context)
        assertTrue(current.status in setOf(
            PhantomVpnService.STATUS_CONNECTED, PhantomVpnService.STATUS_STARTING,
            PhantomVpnService.STATUS_DISCONNECTED, PhantomVpnService.STATUS_ERROR,
        ))
    }
}
