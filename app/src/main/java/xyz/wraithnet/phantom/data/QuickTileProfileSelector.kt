package xyz.wraithnet.phantom.data

/** Pure selection rule reused by the Quick Settings tile. */
object QuickTileProfileSelector {
    fun choose(subscriptions: List<Subscription>, selectedId: String?,
        ownerId: String? = null, pinnedId: String? = null): ServerProfile? =
        ProfileSelection.resolve(subscriptions, ownerId, selectedId, pinnedId)?.second
}
