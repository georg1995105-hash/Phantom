package xyz.wraithnet.phantom.ui.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps each subscription's expanded/collapsed server state across process
 * restarts, app updates, phone/tablet rotation and Android TV navigation.
 *
 * An unset key preserves the former form-factor default: the selected group
 * on phones and all groups on wide tablet/TV. Only explicit user toggles are
 * saved. It never persists a subscription URL or a server configuration.
 */
internal class SubscriptionExpansionStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "phantom_preferences", Context.MODE_PRIVATE
    )
    private val live = mutableStateMapOf<String, Boolean>()

    fun isExpanded(subscriptionId: String, defaultExpanded: Boolean): Boolean =
        live[subscriptionId] ?: prefs.getBoolean(preferenceKey(subscriptionId), defaultExpanded)

    fun setExpanded(subscriptionId: String, expanded: Boolean) {
        live[subscriptionId] = expanded
        prefs.edit().putBoolean(preferenceKey(subscriptionId), expanded).apply()
    }

    companion object {
        private const val PREFIX = "subscription_server_expanded_v1:"

        internal fun preferenceKey(subscriptionId: String): String = PREFIX + subscriptionId
    }
}

@Composable
internal fun rememberSubscriptionExpansionStore(): SubscriptionExpansionStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SubscriptionExpansionStore(context) }
}
