package xyz.wraithnet.phantom.data

/**
 * The runtime supports two independent backends behind a single Android VPN TUN.
 * Keep the import, chooser and latency UI on the same protocol policy.
 */
object ProtocolSupport {
    val xray = setOf("vless", "vmess", "trojan", "ss", "xray", "json",
        "hysteria", "hysteria2", "hy2")
    val singBox = setOf("tuic", "anytls")
    fun isSupported(scheme: String): Boolean = scheme.lowercase() in xray || scheme.lowercase() in singBox
    fun needsConnectedPing(scheme: String): Boolean =
        scheme.lowercase() in setOf("hysteria", "hysteria2", "hy2", "tuic")
}

/** The selected profile is scoped to its parent subscription, not only the link index. */
object ProfileSelection {
    private fun preferred(owner: Subscription): ServerProfile? =
        owner.servers.firstOrNull { it.id == owner.selectedServerId && ProtocolSupport.isSupported(it.scheme) }
            ?: owner.servers.firstOrNull { ProtocolSupport.isSupported(it.scheme) }

    fun resolve(
        subscriptions: List<Subscription>,
        selectedSubscriptionId: String?,
        selectedServerId: String?,
        pinnedSubscriptionId: String?,
    ): Pair<Subscription, ServerProfile>? {
        val owner = subscriptions.firstOrNull { it.id == selectedSubscriptionId }
        owner?.servers?.firstOrNull { it.id == selectedServerId &&
            ProtocolSupport.isSupported(it.scheme) }?.let { return owner to it }
        // If the subscription refreshed and server IDs changed, stay inside
        // the explicitly selected subscription. Never jump to another owner.
        owner?.let { subscription ->
            preferred(subscription)?.let { return subscription to it }
        }
        // Backward compatibility with 0.2.12, which saved only selected_server_id.
        if (selectedSubscriptionId == null && selectedServerId != null) {
            subscriptions.forEach { sub ->
                sub.servers.firstOrNull { it.id == selectedServerId && ProtocolSupport.isSupported(it.scheme) }
                    ?.let { return sub to it }
            }
        }
        // If a subscription was deleted/refreshed, select a real supported profile.
        val fallbackOrder = subscriptions.sortedByDescending { it.id == pinnedSubscriptionId }
        fallbackOrder.forEach { sub ->
            preferred(sub)?.let { return sub to it }
        }
        return null
    }
}
