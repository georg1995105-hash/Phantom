package xyz.wraithnet.phantom.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import xyz.wraithnet.phantom.data.XrayConfigBuilder

/**
 * Exercises the pinned native Xray SOCKS5 outbound with a local deterministic
 * SOCKS5 fixture. No TUIC credentials, Internet dependency or Android VPN consent.
 */
@RunWith(AndroidJUnit4::class)
class XraySocks5OutboundIntegrationTest {
    @Test fun currentXraySocksOutboundConnectsThroughRealNativeCore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        XrayEnvironment.initialize(context)

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 10000
            val reached = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val worker = Thread {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 7000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        fun read(n: Int): ByteArray {
                            val data = ByteArray(n)
                            var offset = 0
                            while (offset < n) {
                                val count = input.read(data, offset, n - offset)
                                require(count > 0)
                                offset += count
                            }
                            return data
                        }
                        // SOCKS5 greeting and noauth reply.
                        val header = read(2)
                        require(header[0] == 5.toByte())
                        read(header[1].toInt() and 255)
                        output.write(byteArrayOf(5, 0))
                        output.flush()
                        val req = read(4)
                        require(req[0] == 5.toByte() && req[1] == 1.toByte())
                        when (req[3].toInt() and 255) {
                            1 -> read(4)
                            3 -> read(read(1)[0].toInt() and 255)
                            4 -> read(16)
                            else -> error("Unexpected SOCKS5 address type")
                        }
                        read(2)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        output.flush()
                        val request = StringBuilder()
                        while (request.length < 4096 && !request.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            require(next >= 0)
                            request.append(next.toChar())
                        }
                        require(request.startsWith("GET ") || request.startsWith("HEAD "))
                        reached.set(true)
                        output.write(
                            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII))
                        output.flush()
                    }
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true; start() }

            val config = JSONObject(XrayConfigBuilder.buildSocksBridge(listener.localPort))
            // measureOutboundDelay tests the outgoing transport only, no fake TUN fd.
            config.put("inbounds", JSONArray())
            val result = Libv2ray.measureOutboundDelay(
                config.toString(), "http://192.0.2.1:80/generate_204")
            assertTrue("Native Xray failed SOCKS5 outbound: delay=$result", result > 0L)
            assertTrue("Native Xray did not use local SOCKS5 outbound", reached.get())
            assertTrue("SOCKS5 fixture did not complete", done.await(10, TimeUnit.SECONDS))
        }
    }
}
