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
