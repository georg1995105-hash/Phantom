package xyz.wraithnet.phantom.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The only durable writer is VpnSettingsProvider in the app process. */
@RunWith(AndroidJUnit4::class)
class VpnSettingsIpcTest {
    @Test fun runtimeWritesPreserveUnrelatedSettingsAndRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val names = listOf("vpn_requested", "vpn_last_profile", "vpn_running",
            "vpn_status", "vpn_connected_since_elapsed")
        val original = names.associateWith { key -> prefs.all[key] }
        val existed = names.associateWith(prefs::contains)
        try {
            assertTrue(VpnSettingsBridge.persist(context,
                requested = true, lastProfile = "vless://test@vpn.example:443",
                running = true, status = "degraded", since = 1234L))
            val snapshot = VpnSettingsBridge.persisted(context)
            assertTrue(snapshot.requested)
            assertEquals("vless://test@vpn.example:443", snapshot.lastProfile)
            assertEquals("degraded", prefs.getString("vpn_status", null))
            assertEquals(1234L, prefs.getLong("vpn_connected_since_elapsed", -1))
            // A partial runtime event must not overwrite a profile or UI setting.
            assertTrue(VpnSettingsBridge.persist(context, running = false, since = 0L))
            assertEquals("vless://test@vpn.example:443",
                prefs.getString("vpn_last_profile", null))
            assertFalse(prefs.getBoolean("vpn_running", true))
            assertEquals(0L, prefs.getLong("vpn_connected_since_elapsed", -1L))
        } finally {
            val edit = prefs.edit()
            for (key in names) {
                if (existed[key] != true) edit.remove(key)
                else when (val value = original[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Long -> edit.putLong(key, value)
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                }
            }
            assertTrue(edit.commit())
        }
    }
}
