package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class QuickTileProfileSelectorTest {
    private val first = ServerProfile(id = "a", name = "NL", scheme = "vless", raw = "vless://first")
    private val second = ServerProfile(id = "b", name = "SE", scheme = "vless", raw = "vless://second")
    private val sub = Subscription(id = "1", title = "Subscription", source = "test",
        servers = listOf(first, second))

    @Test fun selectedServerIsUsedRatherThanFirst() {
        assertEquals(second.raw, QuickTileProfileSelector.choose(listOf(sub), "b")?.raw)
    }

    @Test fun missingSelectionFallsBackToAvailableServer() {
        assertEquals(first.raw, QuickTileProfileSelector.choose(listOf(sub), "missing")?.raw)
        assertEquals(first.raw, QuickTileProfileSelector.choose(listOf(sub), null)?.raw)
    }

    @Test fun sameIdInTwoImportsUsesTheSelectedSubscription() {
        val other = sub.copy(id = "2", servers = listOf(first.copy(raw = "second-source")))
        assertEquals("second-source", QuickTileProfileSelector.choose(listOf(sub, other), "a", "2")?.raw)
    }

    @Test fun noSubscriptionNeverStartsEmptyTunnel() {
        assertNull(QuickTileProfileSelector.choose(emptyList(), "a"))
    }
}
