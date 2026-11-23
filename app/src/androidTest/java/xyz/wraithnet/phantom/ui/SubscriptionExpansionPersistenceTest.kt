package xyz.wraithnet.phantom.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.ui.home.SubscriptionExpansionStore

/**
 * Regression: a user's own collapsed/expanded choice must survive a new
 * Activity/composition and a full app process relaunch. Different providers
 * must not overwrite each other's state.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionExpansionPersistenceTest {
    @Test fun restoresBothExpandedAndCollapsedStatesIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val first = "ui-expansion-test-remote"
        val second = "ui-expansion-test-backup"
        val keys = listOf(first, second).map(SubscriptionExpansionStore::preferenceKey)
        val previous = keys.associateWith { key ->
            if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        }
        try {
            keys.forEach { prefs.edit().remove(it).commit() }
            val session = SubscriptionExpansionStore(context)
            assertTrue(session.isExpanded(first, true))
            assertFalse(session.isExpanded(second, false))
            session.setExpanded(first, false)
            session.setExpanded(second, true)

            // Fresh instance with no Compose state: reads from SharedPreferences.
            val afterRestart = SubscriptionExpansionStore(context)
            assertFalse(afterRestart.isExpanded(first, true))
            assertTrue(afterRestart.isExpanded(second, false))
            afterRestart.setExpanded(first, true)
            assertTrue(SubscriptionExpansionStore(context).isExpanded(first, false))
            assertTrue(SubscriptionExpansionStore(context).isExpanded(second, false))
        } finally {
            prefs.edit().apply {
                previous.forEach { (key, value) ->
                    if (value == null) remove(key) else putBoolean(key, value)
                }
            }.commit()
        }
    }

    @Test fun eachSubscriptionGetsItsOwnPreferenceKey() {
        val first = SubscriptionExpansionStore.preferenceKey("first")
        val second = SubscriptionExpansionStore.preferenceKey("second")
        assertFalse(first == second)
        assertTrue(first.startsWith("subscription_server_expanded_v1:"))
        assertEquals(first, SubscriptionExpansionStore.preferenceKey("first"))
    }
}
