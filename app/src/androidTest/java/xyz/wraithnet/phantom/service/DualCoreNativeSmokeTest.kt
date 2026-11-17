package xyz.wraithnet.phantom.service

import android.util.Log
import android.os.Process
import org.junit.Assert.assertNotEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android process isolation check: Xray JNI in the main process and
 * sing-box JNI in a separate process. JVM tests cannot detect Go runtime conflicts.
 * This does not establish VPN permission or send traffic over an actual remote TUIC server.
 */
@RunWith(AndroidJUnit4::class)
class DualCoreNativeSmokeTest {
    @Test fun bothGoRuntimesLoadAndNativeSocksStarts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i("PhantomNativeSmoke", "BEFORE_XRAY_INITIALIZE")
        XrayEnvironment.initialize(context)
        Log.i("PhantomNativeSmoke", "AFTER_XRAY_INITIALIZE")
        val bridge = SingBoxBridge(context)
        val link = "tuic://00000000-0000-0000-0000-000000000001:testPassword@" +
            "127.0.0.1:25443?sni=example.org&alpn=h3"
        try {
            Log.i("PhantomNativeSmoke", "BEFORE_SINGBOX_START")
            val port = bridge.start(link)
            Log.i("PhantomNativeSmoke", "AFTER_SINGBOX_START pid=" + bridge.workerPid)
            assertTrue("sing-box must have a distinct Android PID",
                bridge.workerPid != android.os.Process.myPid())
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
            Log.i("PhantomNativeSmoke", "SOCKS_READY=$ready")
            assertTrue("sing-box native SOCKS did not bind", ready)
        } finally {
            bridge.stop()
        }
    }
}
