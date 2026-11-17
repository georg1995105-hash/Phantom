package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class ProtocolSupportTest {
    private fun server(id: String, scheme: String) = ServerProfile(
        id = id, name = id, scheme = scheme, raw = "$scheme://example.com"
    )

    @Test fun supportedProtocolsIncludeBothEngines() {
        listOf("vless", "vmess", "trojan", "ss", "xray", "hy2", "hysteria2",
            "tuic", "anytls").forEach { assertTrue(it, ProtocolSupport.isSupported(it)) }
        assertFalse(ProtocolSupport.isSupported("wireguard"))
        assertTrue(ProtocolSupport.needsConnectedPing("tuic"))
        assertTrue(ProtocolSupport.needsConnectedPing("hysteria2"))
        assertFalse(ProtocolSupport.needsConnectedPing("anytls"))
    }

    @Test fun selectingTuicRemainsStableAfterMainSubscriptionImport() {
        val tuic = Subscription("test", "test", "tuic://", listOf(server("tuic-1", "tuic")))
        val main = Subscription("main", "main", "https://example.com",
            listOf(server("hy2-1", "hy2"), server("vless-1", "vless")))
        assertEquals("tuic", ProfileSelection.resolve(
            listOf(tuic, main), "test", "tuic-1", null)?.second?.scheme)
        assertEquals("hy2", ProfileSelection.resolve(
            listOf(tuic, main), "main", "hy2-1", "test")?.second?.scheme)
    }

    @Test fun duplicateServerIdsCannotHijackSelection() {
        val a = Subscription("a", "a", "a", listOf(server("same", "tuic")))
        val b = Subscription("b", "b", "b", listOf(server("same", "vless")))
        assertEquals("vless", ProfileSelection.resolve(
            listOf(a,b), "b", "same", null)?.second?.scheme)
    }

    @Test fun staleSelectionsFallBackToPinnedSupportedProfile() {
        val invalid = Subscription("old", "old", "a", listOf(server("w", "wireguard")))
        val tuic = Subscription("test", "test", "b", listOf(server("t", "tuic")))
        assertEquals("test", ProfileSelection.resolve(
            listOf(invalid,tuic), "deleted", "stale", "test")?.first?.id)
        assertNull(ProfileSelection.resolve(listOf(invalid), "deleted", "stale", null))
    }

    @Test fun legacySavedServerIdStillWorks() {
        val tuic = Subscription("tuic", "tuic", "tuic", listOf(server("t", "tuic")))
        assertEquals("t", ProfileSelection.resolve(
            listOf(tuic), null, "t", null)?.second?.id)
    }
}
