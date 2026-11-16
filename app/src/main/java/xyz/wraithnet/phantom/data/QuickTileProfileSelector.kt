package xyz.wraithnet.phantom.data

/** Pure selection rule reused by the Quick Settings tile. */
object QuickTileProfileSelector {
    fun choose(subscriptions: List<Subscription>, selectedId: String?): ServerProfile? {
        val servers = subscriptions.flatMap { it.servers }
        return servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    }
}
