package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FragmentationOptionsTest {
    private val xray = """{"outbounds":[{"protocol":"vless",
        "streamSettings":{"network":"xhttp","security":"reality"}},
        {"protocol":"hysteria","streamSettings":{"network":"hysteria","security":"tls"}},
        {"protocol":"freedom"}]}"""

    @Test fun disabledPreservesProviderConfig() {
        assertEquals(xray, FragmentationOptions().apply(xray))
    }

    @Test fun onlyTcpProxyGetsFinalmaskFragment() {
        val root = JSONObject(FragmentationOptions(true, "80-120", "3-10").apply(xray))
        val outbounds = root.getJSONArray("outbounds")
        val tcp = outbounds.getJSONObject(0).getJSONObject("streamSettings")
            .getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals("fragment", tcp.getJSONObject(0).getString("type"))
        assertEquals("tlshello", tcp.getJSONObject(0).getJSONObject("settings").getString("packets"))
        assertFalse(outbounds.getJSONObject(1).getJSONObject("streamSettings").has("finalmask"))
    }

    @Test fun invalidRangesRejectedAndExistingFragmentPreserved() {
        assertFalse(FragmentationOptions(true, "0-0", "5-10").isValid())
        val once = FragmentationOptions(true).apply(xray)
        val twice = FragmentationOptions(true).apply(once)
        val out = JSONObject(twice).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals(1, out.length())
    }
}
