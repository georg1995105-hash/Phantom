package xyz.wraithnet.phantom.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SharedPreferences recovery and serializable UI/Worker mutations. */
@RunWith(AndroidJUnit4::class)
class SubscriptionStoreSafetyTest {
    @Test fun legacySnapshotsMigrateToKeystoreWithoutChangingProfilesOrSettings() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name: String,mode: Int) = super.getSharedPreferences("secure-migration-$name",mode)
        }
        val prefs = context.getSharedPreferences("phantom_data",Context.MODE_PRIVATE)
        val legacy = """[{"id":"old-owner","title":"Legacy","source":"https://example.invalid/private-subscription","servers":[{"id":"old-server","name":"Node","scheme":"vless","raw":"vless://00000000-0000-0000-0000-000000000001@example.invalid:443"}],"customTitle":"My subscription","selectedServerId":"old-server"}]"""
        prefs.edit().clear().putString("subscriptions",legacy).putString("subscriptions_previous",legacy)
            .putString("device_id","same-device").commit()
        try {
            val before = SubscriptionStore(context).read()
            assertEquals("old-owner",before.single().id)
            assertEquals("My subscription",before.single().customTitle)
            for (key in listOf("subscriptions","subscriptions_previous")) {
                val stored = prefs.getString(key,"")!!
                assertTrue(SubscriptionCipher.isEncrypted(stored))
                assertFalse(stored.contains("private-subscription"))
                assertFalse(stored.contains("vless://"))
            }
            assertEquals(before,SubscriptionStore(context).read())
            assertEquals("same-device",SubscriptionStore(context).deviceId())
            // Authentication detects altered ciphertext and recovers the last valid snapshot.
            val cipherText = prefs.getString("subscriptions","")!!
            val prefix = "encrypted:v1:"
            val blob = android.util.Base64.decode(cipherText.removePrefix(prefix),android.util.Base64.NO_WRAP)
            blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
            prefs.edit().putString("subscriptions",prefix+android.util.Base64.encodeToString(blob,android.util.Base64.NO_WRAP)).commit()
            assertEquals(before,SubscriptionStore(context).read())
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun previousSnapshotRecoversCorruptCurrentWithoutDiscardingEverySubscription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("phantom_data", Context.MODE_PRIVATE)
        val original = prefs.getString("subscriptions", null)
        val backup = prefs.getString("subscriptions_previous", null)
        try {
            assertTrue(prefs.edit().putString("subscriptions", "[]")
                .remove("subscriptions_previous").commit())
            val store = SubscriptionStore(context)
            val a = SubscriptionParser.parse(
                "vless://00000000-0000-0000-0000-000000000001@a.example:443#A"
            )
            val b = SubscriptionParser.parse(
                "vless://00000000-0000-0000-0000-000000000002@b.example:443#B"
            )
            store.update { current -> current + a }
            store.update { current -> current + b }
            assertEquals(2, store.read().size)

            // Simulate a corrupted active snapshot after a completed commit.
            assertTrue(prefs.edit().putString("subscriptions", "{broken").commit())
            val restored = store.read()
            assertEquals(listOf(a.id), restored.map { it.id })
            assertEquals(listOf(a.id), store.read().map { it.id })
        } finally {
            val editor = prefs.edit()
            if (original == null) editor.remove("subscriptions")
            else editor.putString("subscriptions", original)
            if (backup == null) editor.remove("subscriptions_previous")
            else editor.putString("subscriptions_previous", backup)
            assertTrue(editor.commit())
        }
    }

    @Test fun brokenPrimaryAndBackupFailClosedInsteadOfLosingKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("phantom_data", Context.MODE_PRIVATE)
        val original = prefs.getString("subscriptions", null)
        val backup = prefs.getString("subscriptions_previous", null)
        try {
            assertTrue(prefs.edit().putString("subscriptions", "{damaged")
                .putString("subscriptions_previous", "{also damaged").commit())
            val store = SubscriptionStore(context)
            try {
                store.update { emptyList() }
                fail("Corrupted subscriptions must not be replaced by an empty list")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("поврежден"))
            }
            assertEquals("{damaged", prefs.getString("subscriptions", null))
        } finally {
            val editor = prefs.edit()
            if (original == null) editor.remove("subscriptions")
            else editor.putString("subscriptions", original)
            if (backup == null) editor.remove("subscriptions_previous")
            else editor.putString("subscriptions_previous", backup)
            assertTrue(editor.commit())
        }
    }
}
