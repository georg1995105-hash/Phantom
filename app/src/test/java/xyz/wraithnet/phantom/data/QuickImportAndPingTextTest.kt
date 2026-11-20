package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class QuickImportAndPingTextTest {
    @Test fun acceptedCopiedUrlsAndShareLinks() {
        assertTrue(SubscriptionQuickImport.isSubscriptionLink("  https://vpn.example.com/sub?id=1  "))
        assertTrue(SubscriptionQuickImport.isSubscriptionLink("http://127.0.0.1:8080/sub"))
        assertTrue(SubscriptionQuickImport.isSubscriptionLink(
            "vless://00000000-0000-0000-0000-000000000001@server.example:443#NL"))
        assertTrue(SubscriptionQuickImport.isSubscriptionLink("hysteria2://secret@server.example:443#SE"))
    }

    @Test fun invalidClipboardDoesNotStartImport() {
        listOf("", "   ", "some unrelated text", "https://", "file:///storage/file",
            "javascript://alert(1)", "phantom://pair?token=abc", "https://bad host/sub",
            "https://example.com/a\nhttps://example.com/b").forEach {
            assertFalse(it, SubscriptionQuickImport.isSubscriptionLink(it))
        }
    }

    @Test fun pingDisplaysOnlyMillisecondsAndHasDistinctMeasuringAndMissingStates() {
        for (transport in listOf("TCP", "QUIC", "VPN")) {
            val measured = LatencyProbe.PingResult(97L, transport)
            assertEquals("97 мс", SubscriptionPingText.display(measured, false))
            assertEquals("97 мс", SubscriptionPingText.display(measured, true))
        }
        assertEquals("…", SubscriptionPingText.display(null, true))
        assertEquals("—", SubscriptionPingText.display(null, false))
        assertEquals("—", SubscriptionPingText.display(LatencyProbe.PingResult(0, "TCP"), false))
    }

    @Test fun automaticSelectionUsesOnlyTheOwnersSupportedProfiles() {
        val unsupported = ServerProfile("unsupported", "Other", "ssh", raw = "unsupported")
        val first = ServerProfile("first", "Netherlands", "vless", raw = "first")
        val fastest = ServerProfile("second", "Germany", "hy2", raw = "second")
        val owner = Subscription("a", "First", "", listOf(unsupported, first, fastest))
        val delays = mapOf(unsupported.id to 1L, first.id to 60L, fastest.id to 32L)

        assertEquals(fastest, ProfileSelection.automatic(owner) { delays[it.id] })
        assertEquals(first, ProfileSelection.automatic(owner) { null })
        val imported = ServerProfile("provider", "🇪🇺 Автовыбор", "xray", raw = "{}")
        assertEquals(imported, ProfileSelection.automatic(owner.copy(servers = owner.servers + imported)) {
            delays[it.id]
        })
    }
}
