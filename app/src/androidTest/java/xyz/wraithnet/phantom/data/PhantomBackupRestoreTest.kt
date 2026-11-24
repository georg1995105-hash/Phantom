package xyz.wraithnet.phantom.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhantomBackupRestoreTest {
    @Test fun actualStoreCaptureClearRestoreKeepsAllProfilesAndPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = context.getSharedPreferences("phantom_data", Context.MODE_PRIVATE)
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val savedData = data.all
        val savedPrefs = prefs.all
        fun restorePrefs(target: android.content.SharedPreferences, values: Map<String, *>) {
            val edit = target.edit().clear()
            for ((key, value) in values) when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            assertTrue(edit.commit())
        }
        try {
            assertTrue(data.edit().clear().commit())
            assertTrue(prefs.edit().clear().commit())
            val store = SubscriptionStore(context)
            val a = SubscriptionParser.parse(
                "vless://00000000-0000-0000-0000-000000000001@a.example:443#A"
            ).copy(id = "backup-a", source = "https://example.org/sub")
            val b = SubscriptionParser.parse(
                "vless://00000000-0000-0000-0000-000000000002@b.example:443#B"
            ).copy(id = "backup-b")
            store.write(listOf(a, b))
            val original = store.read()
            val deviceId = store.deviceId()
            val favorite = "${a.id}:${a.servers.first().id}"
            assertTrue(prefs.edit().putString("selected_subscription_id", a.id)
                .putString("selected_server_id", a.servers.first().id)
                .putString("pinned_sub_id", a.id)
                .putStringSet("favorite_servers", setOf(favorite))
                .putBoolean("subscription_server_expanded_v1:backup-a", false)
                .putBoolean("auto_tuic_ping", true).putInt("ping_timeout_seconds", 9)
                .putString("theme", "FOREST").putBoolean("auto_apk_updates", false).commit())
            val archive = PhantomBackup.parse(PhantomBackup.capture(context))
            assertEquals(2, archive.serverCount)
            assertTrue(data.edit().clear().commit())
            assertTrue(prefs.edit().clear().putBoolean("auto_apk_updates", true).commit())
            assertEquals(2, PhantomBackup.restore(context, archive))
            assertEquals(original, SubscriptionStore(context).read())
            assertEquals(deviceId, store.deviceId())
            assertEquals(a.id, prefs.getString("selected_subscription_id", null))
            assertEquals(a.servers.first().id, prefs.getString("selected_server_id", null))
            assertEquals(setOf(favorite), prefs.getStringSet("favorite_servers", emptySet()))
            assertFalse(prefs.getBoolean("subscription_server_expanded_v1:backup-a", true))
            assertTrue(prefs.getBoolean("auto_tuic_ping", false))
            assertEquals(9, prefs.getInt("ping_timeout_seconds", 0))
            assertFalse(prefs.getBoolean("auto_apk_updates", true))
        } finally {
            restorePrefs(data, savedData)
            restorePrefs(prefs, savedPrefs)
        }
    }
}
