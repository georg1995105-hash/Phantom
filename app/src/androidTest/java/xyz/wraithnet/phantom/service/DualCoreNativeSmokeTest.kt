package xyz.wraithnet.phantom.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android JNI check. JVM unit tests cannot detect libbox/Xray Go runtime conflicts.
 * This does not establish VPN permission or send traffic over an actual remote TUIC server.
 */
@RunWith(AndroidJUnit4::class)
class DualCoreNativeSmokeTest {
    @Test fun bothGoRuntimesLoadAndNativeSocksStarts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        XrayEnvironment.initialize(context)
        val bridge = SingBoxBridge(context) { /* not needed until outbound dials */ }
        val link = "tuic://00000000-0000-0000-0000-000000000001:testPassword@" +
            "127.0.0.1:25443?sni=example.org&alpn=h3"
        try {
            val port = bridge.start(link)
            var ready = false
            repeat(20) {
                if (!ready) {
                    ready = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                            socket.isConnected
                        }
                    }.getOrDefault(false)
                    if (!ready) Thread.sleep(150)
                }
            }
            assertTrue("sing-box native SOCKS did not bind", ready)
        } finally {
            bridge.stop()
        }
    }
}
