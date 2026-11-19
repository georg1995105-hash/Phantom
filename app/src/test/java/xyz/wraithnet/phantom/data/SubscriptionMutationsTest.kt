package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class SubscriptionMutationsTest {
    private fun subscription(id: String, source: String, server: String): Subscription =
        Subscription(
            id = id, title = "Подписка $id", source = source,
            servers = listOf(ServerProfile("same-id", server, "vless", raw = "vless://$server")),
            updatedAt = 1L,
        )

    @Test fun refreshingFirstOnlyReplacesFirstEvenWhenServerIdsMatch() {
        val first = subscription("first", "https://a.example/sub", "NL")
        val second = subscription("second", "https://b.example/sub", "SE")
        val fromNetwork = first.copy(id = "random-parser-id", title = "New", 
            servers = listOf(ServerProfile("new-id", "DE", "vless", raw = "vless://DE")),
            updatedAt = 99L)
        val after = SubscriptionMutations.replaceDownloaded(listOf(first, second), first,
            fromNetwork, strict = true)
        assertEquals(2, after.size)
        assertEquals(second, after[1])
        assertEquals("first", after[0].id)
        assertEquals("DE", after[0].servers.single().name)
        assertEquals(99L, after[0].updatedAt)
    }

    @Test fun refreshingSecondDoesNotChangeFirstOrItsSelection() {
        val first = subscription("first", "https://a.example/sub", "NL")
        val second = subscription("second", "https://b.example/sub", "SE")
        val refreshed = second.copy(id = "parser-random", servers = listOf(
            ServerProfile("new-server-id", "FI", "vless", raw = "vless://FI")))
        val after = SubscriptionMutations.replaceDownloaded(listOf(first, second), second,
            refreshed, strict = true)
        assertEquals(first, after[0])
        val chosen = ProfileSelection.resolve(after, "second", "same-id", null)
        assertEquals("second", chosen?.first?.id)
        assertEquals("FI", chosen?.second?.name)
        assertEquals("second", after[1].id)
    }

    @Test fun userRenamingAndUpdateIntervalSurviveAnInFlightRefresh() {
        val original = subscription("a", "https://a.example/sub", "NL")
        val edited = original.copy(customTitle = "Моя подписка", title = "Моя подписка",
            customIntervalSeconds = 7200L, updateIntervalSeconds = 7200L)
        val network = original.copy(title = "Changed by server", updatedAt = 40L)
        val after = SubscriptionMutations.replaceDownloaded(listOf(edited), original,
            network, strict = true).single()
        assertEquals("Моя подписка", after.title)
        assertEquals("Моя подписка", after.customTitle)
        assertEquals(7200L, after.updateIntervalSeconds)
        assertEquals(7200L, after.customIntervalSeconds)
    }

    @Test fun deletedAndReplacedSourcesNeverReturnViaBackgroundRefresh() {
        val a = subscription("a", "https://a.example/sub", "NL")
        val b = subscription("b", "https://b.example/sub", "SE")
        assertEquals(listOf(b), SubscriptionMutations.replaceDownloaded(listOf(b), a,
            a.copy(updatedAt = 100L)))
        val changed = a.copy(source = "https://new.example/sub")
        assertEquals(listOf(changed, b),
            SubscriptionMutations.replaceDownloaded(listOf(changed, b), a,
                a.copy(updatedAt = 100L)))
    }

    @Test fun manualRefreshReportsConflictRatherThanFalseSuccess() {
        val a = subscription("a", "https://a.example/sub", "NL")
        try {
            SubscriptionMutations.replaceDownloaded(emptyList(), a, a, strict = true)
            fail("Manual update must fail if subscription was deleted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("удалена"))
        }
    }

    @Test fun lateBackgroundResponseCannotOverwriteNewerManualRefresh() {
        val original = subscription("a", "https://a.example/sub", "NL")
        val another = subscription("b", "https://b.example/sub", "SE")
        val latest = original.copy(updatedAt = 500L,
            servers = listOf(ServerProfile("new-id", "FI", "vless", raw = "vless://FI")))
        val staleResponse = original.copy(updatedAt = 800L,
            servers = listOf(ServerProfile("old-id", "NL old", "vless", raw = "vless://old")))
        assertEquals(listOf(latest, another),
            SubscriptionMutations.replaceDownloaded(listOf(latest, another),
                original, staleResponse))
        try {
            SubscriptionMutations.replaceDownloaded(listOf(latest), original,
                staleResponse, strict = true)
            fail("A stale manual refresh must report a conflict")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("другим запросом"))
        }
    }

    @Test fun explicitOwnerIsNeverConfusedWithAnotherSubscriptionWithSameServerId() {
        val a = subscription("a", "https://a.example/sub", "NL")
        val b = subscription("b", "https://b.example/sub", "DE")
        val chosen = ProfileSelection.resolve(listOf(a, b), "b", "same-id", null)
        assertEquals(b, chosen?.first)
        assertEquals("DE", chosen?.second?.name)
    }
}
