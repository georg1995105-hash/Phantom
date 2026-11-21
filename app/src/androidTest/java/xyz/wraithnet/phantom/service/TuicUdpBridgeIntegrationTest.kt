package xyz.wraithnet.phantom.service

import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.XrayConfigBuilder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Real UDP over both native engines:
 * app -> Xray SOCKS5 UDP inbound -> Xray SOCKS5 outbound ->
 * sing-box SOCKS5 inbound -> TUIC/QUIC -> server-side UDP DNS responder.
 *
 * Unlike an HTTP CONNECT probe, this exercises the failing browser UDP path.
 * The origin is an isolated loopback peer on the GitHub Actions runner.
 */
@RunWith(AndroidJUnit4::class)
class TuicUdpBridgeIntegrationTest {
    private fun readExact(input: java.io.InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var consumed = 0
        while (consumed < size) {
            val count = input.read(bytes, consumed, size - consumed)
            require(count > 0) { "SOCKS5 UDP control channel closed" }
            consumed += count
        }
        return bytes
    }

    private fun dnsQuery(id: Int): ByteArray {
        val name = byteArrayOf(5) + "entry".toByteArray() + byteArrayOf(7) +
            "phantom".toByteArray() + byteArrayOf(4) + "test".toByteArray() + byteArrayOf(0)
        val header = byteArrayOf(
            (id ushr 8).toByte(), id.toByte(), 1, 0,
            0, 1, 0, 0, 0, 0, 0, 0
        )
        return header + name + byteArrayOf(0, 1, 0, 1)
    }

    private fun verifyUdpRoundTrip(xraySocksPort: Int, id: Int) {
        Socket("127.0.0.1", xraySocksPort).use { control ->
            control.soTimeout = 8_000
            val input = control.getInputStream()
            val output = control.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            assertArrayEquals(byteArrayOf(5, 0), readExact(input, 2))
            // UDP ASSOCIATE with ephemeral local address/port.
            output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            val reply = readExact(input, 4)
            assertEquals("SOCKS UDP ASSOCIATE rejected", 0, reply[1].toInt() and 255)
            val addressSize = when (reply[3].toInt() and 255) {
                1 -> 4
                4 -> 16
                3 -> readExact(input, 1)[0].toInt() and 255
                else -> error("Invalid SOCKS5 address type")
            }
            val bindAddress = InetAddress.getByAddress(readExact(input, addressSize))
            val port = readExact(input, 2)
            val udpPort = (port[0].toInt() and 255) * 256 + (port[1].toInt() and 255)
            assertTrue("No UDP relay port", udpPort > 0)
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { udp ->
                udp.soTimeout = 8_000
                val origin = dnsQuery(id)
                // SOCKS5 UDP header for the server-side responder 127.0.0.1:34303.
                val envelope = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1,
                    (34303 ushr 8).toByte(), 34303.toByte()) + origin
                val actualRelay = if (bindAddress.isAnyLocalAddress)
                    InetAddress.getByName("127.0.0.1") else bindAddress
                udp.send(DatagramPacket(envelope, envelope.size, actualRelay, udpPort))
                val response = DatagramPacket(ByteArray(2048), 2048)
                udp.receive(response)
                val frame = response.data.copyOfRange(0, response.length)
                assertTrue("Invalid SOCKS5 UDP reply", frame.size > 12 &&
                    frame[0].toInt() == 0 && frame[1].toInt() == 0)
                val payloadStart = when (frame[3].toInt() and 255) {
                    1 -> 10
                    4 -> 22
                    3 -> 7 + (frame[4].toInt() and 255)
                    else -> error("Invalid SOCKS5 UDP response type")
                }
                val body = frame.copyOfRange(payloadStart, frame.size)
                assertTrue("No DNS response over TUIC UDP", body.size >= 12)
                assertEquals(id, (body[0].toInt() and 255) * 256 +
                    (body[1].toInt() and 255))
                assertEquals("DNS success flag missing", 0x80, body[2].toInt() and 0x80)
            }
        }
    }

    @Test fun udpDnsSurvivesFullTwoCoreTuicBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue("Requires CI TUIC protocol peer", "peers.json" in assets.list("").orEmpty())
        val context = instrumentation.targetContext
        val permission = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} ACTIVATE_VPN allow")
        ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        assertNull("VPN permission was not granted", VpnService.prepare(context))
        val cases = JSONObject(assets.open("peers.json").bufferedReader().use { it.readText() })
        val profile = SubscriptionParser.parse(cases.getString("tuic"), "tuic-udp")
            .servers.single()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }
        XrayEnvironment.initialize(context)
        val bridge = SingBoxBridge(context)
        try {
            val config = JSONObject(XrayConfigBuilder.buildSocksBridge(bridge.start(profile.raw)))
            val inbound = config.getJSONArray("inbounds").getJSONObject(0)
            inbound.put("protocol", "socks").put("listen", "127.0.0.1").put("port", port)
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                .remove("sniffing")
            val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup() = 0L
                override fun shutdown() = 0L
                override fun onEmitStatus(code: Long, message: String) = 0L
            })
            try {
                core.startLoop(config.toString(), 0)
                for (round in 0..1) verifyUdpRoundTrip(port, 0x5100 + round)
            } finally {
                core.stopLoop()
            }
        } finally {
            bridge.stop()
        }
    }
}
