package xyz.wraithnet.phantom.data

import java.util.UUID

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
        if (latest.updatedAt != original.updatedAt) {
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
            selectedServerId = latest.selectedServerId,
            updatedAt = maxOf(downloaded.updatedAt, latest.updatedAt + 1),
            title = latest.customTitle ?: downloaded.title,
            updateIntervalSeconds =
                latest.customIntervalSeconds ?: downloaded.updateIntervalSeconds,
            servers = retainServerIds(
                latest.servers, downloaded.servers, SubscriptionFetcher.usesProviderJson(latest.source)
            ),
        )
        return current.toMutableList().apply { set(ownerIndex, saved) }
    }

    private fun retainServerIds(
        previous: List<ServerProfile>,
        incoming: List<ServerProfile>,
        matchProviderNames: Boolean,
    ): List<ServerProfile> {
        val previousByContent = previous.groupBy { it.raw }.mapValues { ArrayDeque(it.value) }
        val retained = incoming.map { previousByContent[it.raw]?.removeFirstOrNull()?.id }
        val used = retained.filterNotNull().toMutableSet()
        // The same Phantom VPN profiles have identical remarks in the links
        // and Xray JSON responses. Match only unique names within this owner.
        val oldByName = if (matchProviderNames) previous.groupBy { it.name } else emptyMap()
        val incomingByName = if (matchProviderNames) incoming.groupingBy { it.name }.eachCount() else emptyMap()
        val matched = incoming.mapIndexed { index, profile ->
            retained[index] ?: oldByName[profile.name]?.singleOrNull()?.id
                ?.takeIf { incomingByName[profile.name] == 1 && used.add(it) }
        }
        return incoming.mapIndexed { index, profile ->
            val id = matched[index] ?: if (used.add(profile.id)) profile.id else UUID.randomUUID().toString()
            profile.copy(id = id)
        }
    }
}
