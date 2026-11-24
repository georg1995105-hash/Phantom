package xyz.wraithnet.phantom.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.XrayConfigBuilder

/**
 * Real pinned Xray JNI with provider inboundTag routing.
 * All endpoints are loopback; no production credentials or Internet needed.
 */
@RunWith(AndroidJUnit4::class)
class XrayProviderRoutingAndroidTest {
    @Test fun providerTunRuleOverridesDefaultBlackholeInNativeCore() {
        XrayEnvironment.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { origin ->
            origin.soTimeout = 12_000
            val responseSent = CountDownLatch(1)
            val peer = Thread({
                runCatching {
                    origin.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val bytes = ByteArrayOutputStream()
                        while (bytes.size() < 4096) {
                            val b = input.read()
                            if (b < 0) break
                            bytes.write(b)
                            if (bytes.toString("US-ASCII").endsWith("\r\n\r\n")) break
                        }
                        val request = bytes.toString("US-ASCII")
                        require(request.startsWith("GET /native-route"))
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        responseSent.countDown()
                    }
                }
            }, "Phantom-Local-Xray-Peer").apply { isDaemon = true; start() }
            val proxyPort = ServerSocket(0, 1, loopback).use { it.localPort }
            val raw = """{
              "inbounds":[
                {"tag":"android-entry","protocol":"tun","settings":{"userLevel":8}},
                {"tag":"socks-policy","protocol":"socks"}
              ],
              "outbounds":[
                {"tag":"default-block","protocol":"blackhole"},
                {"tag":"nl-proxy","protocol":"freedom"}
              ],
              "routing":{"rules":[
                {"type":"field","inboundTag":["socks-policy"],"outboundTag":"default-block"},
                {"type":"field","inboundTag":["android-entry"],"outboundTag":"nl-proxy"}
              ]}
            }"""
            val config = JSONObject(XrayConfigBuilder.build(ServerProfile(
                "local", "Native route fixture", "xray", raw = raw)))
            val inbound = config.getJSONArray("inbounds").getJSONObject(0)
            assertEquals("android-entry", inbound.getString("tag"))
            inbound.put("protocol", "http").put("listen", "127.0.0.1").put("port", proxyPort)
            inbound.remove("settings")
            // Test HTTP inbound uses same provider tag/rules as Android TUN.
            val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(code: Long, message: String): Long = 0L
            })
            try {
                core.startLoop(config.toString(), 0)
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                val url = URL("http://127.0.0.1:${origin.localPort}/native-route")
                val request = url.openConnection(proxy) as HttpURLConnection
                try {
                    request.connectTimeout = 5_000
                    request.readTimeout = 5_000
                    assertEquals("Provider TUN tag must select freedom, not first blackhole",
                        204, request.responseCode)
                } finally {
                    request.disconnect()
                }
                assertTrue("Native Xray never forwarded to expected loopback destination",
                    responseSent.await(5, TimeUnit.SECONDS))
            } finally {
                core.stopLoop()
                peer.join(1000)
            }
        }
    }
}
