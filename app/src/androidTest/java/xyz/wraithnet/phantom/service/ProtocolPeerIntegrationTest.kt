package xyz.wraithnet.phantom.service

import androidx.test.platform.app.InstrumentationRegistry
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.wraithnet.phantom.data.SingBoxBridgeConfig
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.XrayConfigBuilder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL

class ProtocolPeerIntegrationTest {
    @Test fun providerRoutingReachesXhttpPeerWithoutHittingAServiceRule() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        org.junit.Assume.assumeTrue("Requires CI protocol peers", "peers.json" in assets.list("").orEmpty())
        val cases = JSONObject(assets.open("peers.json").bufferedReader().use { it.readText() })
        val server = SubscriptionParser.parse(cases.getString("VLESS-XHTTP")).servers.single()
        val outbound = JSONObject(XrayConfigBuilder.build(server)).getJSONArray("outbounds").getJSONObject(0)
        val provider = JSONObject("""{
            "remarks":"Автовыбор",
            "inbounds":[{"tag":"monitor","protocol":"dokodemo-door"},{"tag":"client","protocol":"socks"}],
            "outbounds":[{"tag":"block","protocol":"blackhole"}],
            "routing":{"balancers":[{"tag":"auto","selector":["proxy"]}],"rules":[
                {"type":"field","inboundTag":["monitor"],"outboundTag":"block"},
                {"type":"field","inboundTag":["client"],"balancerTag":"auto"}]}
        }""")
        provider.getJSONArray("outbounds").put(outbound)
        val profile = SubscriptionParser.parse(provider.toString()).servers.single()
        val config = JSONObject(XrayConfigBuilder.build(profile))
        val port = ServerSocket(0).use { it.localPort }
        // Exercise the effective routing in the real core without changing Android VPN consent.
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        inbound.put("protocol", "http").put("listen", "127.0.0.1").put("port", port)
        inbound.remove("settings")
        XrayEnvironment.initialize(instrumentation.targetContext)
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String): Long = 0
        })
        try {
            core.startLoop(config.toString(), 0)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            val request = URL("http://127.0.0.1:34301/ProviderRouting").openConnection(proxy)
                as HttpURLConnection
            try {
                request.connectTimeout = 4_000
                request.readTimeout = 4_000
                assertEquals(204, request.responseCode)
            } finally {
                request.disconnect()
            }
        } finally {
            core.stopLoop()
        }
    }

    @Test fun importedLinksReachTheRunnerThroughTheirOwnProtocolPeer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        org.junit.Assume.assumeTrue("Requires CI protocol peers", "peers.json" in assets.list("").orEmpty())
        val cases = JSONObject(assets.open("peers.json").bufferedReader().use { it.readText() })
        val context = instrumentation.targetContext
        // An authorized app is required for the real sing-box socket protect()
        // callback, even when this test omits the Android TUN inbound.
        val grant = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} ACTIVATE_VPN allow")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(grant).use { it.readBytes() }
        org.junit.Assert.assertNull("VPN consent was not granted", android.net.VpnService.prepare(context))
        XrayEnvironment.initialize(context)
        val failures = mutableListOf<String>()
        for (name in cases.keys()) {
            var bridge: SingBoxBridge? = null
            try {
                val server = SubscriptionParser.parse(cases.getString(name)).servers.single()
                val config = if (SingBoxBridgeConfig.requiresSingBox(server)) {
                    val engine = SingBoxBridge(context)
                    bridge = engine
                    XrayConfigBuilder.buildSocksBridge(engine.start(server.raw))
                } else XrayConfigBuilder.build(server)
                val probe = JSONObject(config).put("inbounds", JSONArray())
                // Loopback is the peer's loopback. No HTTP service exists on the Android side.
                val delay = Libv2ray.measureOutboundDelay(probe.toString(), "http://127.0.0.1:34301/$name")
                assertTrue("$name did not reach the server-side origin (delay=$delay)", delay > 0)
                android.util.Log.i("PhantomProtocolTest", "$name PASS")
            } catch (error: Throwable) {
                failures.add("$name: ${error.message?.take(300)}")
            } finally {
                bridge?.stop()
            }
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }
}
