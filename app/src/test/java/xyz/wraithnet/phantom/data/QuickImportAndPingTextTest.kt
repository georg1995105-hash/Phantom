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

    @Test fun providerLinkDecodesExactlyOnceAndMatchesPlainSubscription() {
        val original = "https://vpn.example.com/sub%2Fid?x=a%2Bb&y=1+2"
        val wrapped = "phantom://import?url=https%3A%2F%2Fvpn.example.com%2Fsub%252Fid%3Fx%3Da%252Bb%26y%3D1%2B2"
        assertEquals(original, SubscriptionQuickImport.unwrap(wrapped))
        assertEquals(original, SubscriptionQuickImport.unwrap(original))
        assertTrue(SubscriptionQuickImport.isSubscriptionLink(wrapped))
    }

    @Test fun rejectsMalformedOrAmbiguousImportLinks() {
        listOf("phantom://import", "phantom://import?url=",
            "phantom://import?url=https%3A%2F%2Fexample.org%2Fsub&url=other",
            "phantom://import?url=https%3A%2F%2Fexample.org%2Fsub#other",
            "phantom://import?url=https%3A%2F%2Fuser%40example.org",
            "phantom://import?url=file%3A%2F%2F%2Fetc%2Fpasswd",
            "phantom://import?url=https%3A%2F%2Fexample.org%2F%0A",
            "phantom://import?url=https%3A%2F%2Fexample.org%2F%ZZ",
            "phantom://import?url=https%253A%252F%252Fexample.org",
            "phantom://import?url=https://example.org/sub",
            "phantom://import.evil?url=https%3A%2F%2Fexample.org"
        ).forEach {
            assertFalse(it, SubscriptionQuickImport.isSubscriptionLink(it))
            assertThrows(IllegalArgumentException::class.java) { SubscriptionQuickImport.unwrap(it) }
        }
        val pairing = "phantom://pair?token=abc"
        assertEquals(pairing, SubscriptionQuickImport.unwrap(pairing))
    }

    @Test fun pingDisplaysOnlyMillisecondsAndHasDistinctMeasuringAndMissingStates() {
        assertEquals("97 мс", SubscriptionPingText.display(97L, false))
        assertEquals("…", SubscriptionPingText.display(97L, true))
        assertEquals("…", SubscriptionPingText.display(null, true))
        assertEquals("—", SubscriptionPingText.display(null, false))
        assertEquals("—", SubscriptionPingText.display(0L, false))
    }

}
