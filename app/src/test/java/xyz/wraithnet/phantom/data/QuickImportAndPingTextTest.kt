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
            assertEquals("…", SubscriptionPingText.display(measured, true))
        }
        assertEquals("…", SubscriptionPingText.display(null, true))
        assertEquals("—", SubscriptionPingText.display(null, false))
        assertEquals("—", SubscriptionPingText.display(LatencyProbe.PingResult(0, "TCP"), false))
    }
}
