package xyz.wraithnet.phantom.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VpnProfilePreflightTest {
    private fun check(raw: String, dns: VpnDnsSettings = VpnDnsSettings(),
        fragment: FragmentationOptions = FragmentationOptions()) =
        VpnProfilePreflight.prepare(raw, dns, fragment)

    @Test fun invalidFullJsonFailsBeforeNativeVpnSwitch() {
        val exception = runCatching { check("""{"outbounds":[]}""") }.exceptionOrNull()
        assertTrue(exception is IllegalArgumentException)
    }

    @Test fun conflictingInboundRoutesAreRejectedBeforeVpnSwitch() {
        val raw = """{"inbounds":[{"tag":"socks","protocol":"socks"},
            {"tag":"http","protocol":"http"}],
            "outbounds":[{"tag":"proxy","protocol":"vless"}],
            "routing":{"rules":[{"type":"field","inboundTag":["http"],"outboundTag":"proxy"}]}}"""
        assertTrue(runCatching { check(raw) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun validFullJsonRetainsProviderRoutingAndExitChoice() {
        val raw = """{"inbounds":[{"tag":"provider","protocol":"socks"}],
            "outbounds":[{"tag":"nl-exit","protocol":"vless"},{"tag":"deny","protocol":"blackhole"}],
            "routing":{"rules":[{"type":"field","inboundTag":["provider"],
                "outboundTag":"nl-exit"}],
                "balancers":[{"tag":"smart-nl","selector":["nl-exit"],
                "fallbackTag":"deny"}]}}"""
        val prepared = check(raw)
        assertFalse(prepared.usesSingBox)
        val original = JSONObject(raw).getJSONObject("routing")
        val built = JSONObject(requireNotNull(prepared.xrayConfig))
        assertEquals(original.toString(), built.getJSONObject("routing").toString())
        assertEquals("nl-exit", built.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
    }

    @Test fun malformedTuicCredentialsFailWithoutStartingGoWorker() {
        val wrong = "tuic://not-a-uuid:secret@127.0.0.1:443?sni=example.org"
        assertNotNull(runCatching { check(wrong) }.exceptionOrNull())
    }

    @Test fun validTuicIsPreflightedWithoutAllocatingNativeSocket() {
        val link = "tuic://00000000-0000-0000-0000-000000000001:secret@" +
            "127.0.0.1:443?sni=example.org&alpn=h3"
        val prepared = check(link)
        assertEquals("tuic", prepared.scheme)
        assertTrue(prepared.usesSingBox)
        assertNull(prepared.xrayConfig)
    }

    @Test fun invalidDnsAndFragmentationAreRejectedBeforeVpnSwitch() {
        val valid = """{"outbounds":[{"tag":"proxy","protocol":"vless"}]}"""
        val dns = VpnDnsSettings(VpnDnsSettings.Mode.CUSTOM, "192.0.2.999")
        assertNotNull(runCatching { check(valid, dns) }.exceptionOrNull())
        val fragment = FragmentationOptions(enabled = true, lengths = "bad", delays = "3-10")
        assertNotNull(runCatching { check(valid, fragment = fragment) }.exceptionOrNull())
    }
}
