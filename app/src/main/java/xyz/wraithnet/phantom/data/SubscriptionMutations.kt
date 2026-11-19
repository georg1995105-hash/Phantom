package xyz.wraithnet.phantom.data

/**
 * Apply a downloaded subscription to its ORIGINAL owner, never by server name
 * or position in a combined list. Remote responses cannot resurrect a deleted
 * subscription, overwrite a replaced URL or reset user title/interval edits.
 */
object SubscriptionMutations {
    fun replaceDownloaded(
        current: List<Subscription>,
        original: Subscription,
        downloaded: Subscription,
        strict: Boolean = false,
    ): List<Subscription> {
        val ownerIndex = current.indexOfFirst {
            it.id == original.id && it.source == original.source
        }
        if (ownerIndex < 0) {
            require(!strict) {
                "Подписка изменена или удалена во время обновления"
            }
            return current
        }
        val latest = current[ownerIndex]
        // A background response based on an older snapshot cannot overwrite
        // a newer successful refresh of the same subscription.
        if (latest.updatedAt > original.updatedAt) {
            require(!strict) {
                "Подписка уже обновлена другим запросом"
            }
            return current
        }
        val saved = downloaded.copy(
            id = latest.id,
            source = latest.source,
            customTitle = latest.customTitle,
            customIntervalSeconds = latest.customIntervalSeconds,
            title = latest.customTitle ?: downloaded.title,
            updateIntervalSeconds =
                latest.customIntervalSeconds ?: downloaded.updateIntervalSeconds,
        )
        return current.toMutableList().apply { set(ownerIndex, saved) }
    }
}
