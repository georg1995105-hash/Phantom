package xyz.wraithnet.phantom.service

import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    private fun workerAliveForTest(context: Context, pid: Int): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java)
            .runningAppProcesses?.any {
                it.pid == pid && it.processName == "${context.packageName}:singbox"
            } == true

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
            // The native core must remain available while Android's main
            // process does other work; this models the TUN/Xray warm-up period.
            Thread.sleep(2200)
            assertTrue("SOCKS5 must survive a TUN warm-up interval",
                LocalSocks5Probe.check(port).ready)
            assertTrue("Worker must still be live during VPN warm-up",
                bridge.workerStatus().contains("движок запущен"))
            repeat(4) { iteration ->
                val result = LocalSocks5Probe.check(port)
                Log.i("PhantomNativeSmoke", "SOCKS_HANDSHAKE_${iteration}=${result.ready}")
                assertTrue("Local SOCKS5 handshake failed: ${result.reason}", result.ready)
            }
        } finally {
            val previousPid = bridge.workerPid
            bridge.stop()
            assertTrue("Old Go worker still runs after STOP",
                previousPid > 0 && !workerAliveForTest(context, previousPid))
        }
        // The same app must switch back to TUIC after a stop without retaining
        // the previous worker's Go runtime or a stale local SOCKS listener.
        val restarted = SingBoxBridge(context)
        try {
            Log.i("PhantomNativeSmoke", "BEFORE_SECOND_SINGBOX_START")
            // Exercise Android physical-network hostname resolution on the
            // second worker startup, without contacting a real TUIC server.
            val domainLink = link.replace("@127.0.0.1:", "@localhost:")
            val secondPort = restarted.start(domainLink)
            assertTrue("Second sing-box process must be separate from Xray",
                restarted.workerPid != android.os.Process.myPid())
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", secondPort), 1200)
                assertTrue("Restarted sing-box listener not reachable", socket.isConnected)
            }
            Log.i("PhantomNativeSmoke", "SECOND_SOCKS_READY")
            repeat(4) { iteration ->
                val result = LocalSocks5Probe.check(secondPort)
                Log.i("PhantomNativeSmoke", "RESTARTED_SOCKS_HANDSHAKE_${iteration}=${result.ready}")
                assertTrue("Restarted SOCKS5 handshake failed: ${result.reason}", result.ready)
            }
        } finally {
            restarted.stop()
        }
        var lastPid = -1
        repeat(12) { cycle ->
            val fastSwitch = SingBoxBridge(context)
            try {
                val port = fastSwitch.start(link)
                assertTrue("New session reused old Go runtime at switch $cycle",
                    lastPid != fastSwitch.workerPid)
                lastPid = fastSwitch.workerPid
                val result = LocalSocks5Probe.check(port)
                assertTrue("Fast session switch $cycle: ${result.reason}", result.ready)
            } finally {
                fastSwitch.stop()
                assertTrue("Old process survived STOP at cycle $cycle",
                    !workerAliveForTest(context, lastPid))
            }
        }
    }
    /**
     * Regression for the v0.2.18 lease defect: a temporary Binder unbind
     * MUST NOT stop the native core while the started lease is still held.
     */
    @Test fun startedWorkerSurvivesTransientClientUnbind() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = UUID.randomUUID().toString()
        val link = "tuic://00000000-0000-0000-0000-000000000001:testPassword@" +
            "127.0.0.1:25443?sni=example.org&alpn=h3"
        val lease = Intent(context, SingBoxWorkerService::class.java).apply {
            action = SingBoxWorkerService.ACTION_LEASE
            putExtra(SingBoxWorkerService.KEY_SESSION, session)
        }
        assertTrue("Android refused started lease", context.startService(lease) != null)

        fun bind(): Pair<Messenger, ServiceConnection> {
            val connected = CountDownLatch(1)
            var peer: Messenger? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    peer = Messenger(binder)
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    connected.countDown()
                }
            }
            assertTrue("Could not bind worker",
                context.bindService(Intent(context, SingBoxWorkerService::class.java),
                    connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
            assertTrue("Worker bind timed out", connected.await(15, TimeUnit.SECONDS))
            return (peer ?: error("Worker returned no Binder")) to connection
        }

        fun send(peer: Messenger, action: Int, linkOrNull: String? = null): Pair<Int, Int> {
            val done = CountDownLatch(1)
            val result = AtomicInteger(-1)
            val port = AtomicInteger(-1)
            val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    result.set(msg.what)
                    port.set(msg.arg1)
                    done.countDown()
                }
            })
            peer.send(Message.obtain(null, action).apply {
                data = Bundle().apply {
                    putString(SingBoxWorkerService.KEY_SESSION, session)
                    linkOrNull?.let { putString(SingBoxWorkerService.KEY_PROFILE, it) }
                }
                replyTo = receiver
            })
            assertTrue("Worker did not answer $action", done.await(40, TimeUnit.SECONDS))
            return result.get() to port.get()
        }

        var connection: ServiceConnection? = null
        try {
            val (first, firstConnection) = bind()
            connection = firstConnection
            val (started, port) = send(first, SingBoxWorkerService.MSG_START, link)
            assertTrue("First native worker did not start", started == SingBoxWorkerService.MSG_STARTED)
            assertTrue("Initial local SOCKS5 absent", LocalSocks5Probe.check(port).ready)
            context.unbindService(firstConnection)
            connection = null
            Thread.sleep(1000)
            // The service stays alive because its explicit started lease is held.
            val (second, secondConnection) = bind()
            connection = secondConnection
            val (pong, state) = send(second, SingBoxWorkerService.MSG_PING)
            assertTrue("Rebound worker did not answer PING", pong == SingBoxWorkerService.MSG_PONG)
            assertTrue("Native core was stopped on temporary unbind", state == 1)
            assertTrue("SOCKS5 died on temporary unbind", LocalSocks5Probe.check(port).ready)
            second.send(Message.obtain(null, SingBoxWorkerService.MSG_STOP).apply {
                data = Bundle().apply { putString(SingBoxWorkerService.KEY_SESSION, session) }
            })
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
            context.stopService(Intent(context, SingBoxWorkerService::class.java))
        }
    }

    @Test fun staleStopCannotKillReplacementWorker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = "tuic://00000000-0000-0000-0000-000000000001:testPassword@" +
            "127.0.0.1:25443?sni=example.org&alpn=h3"
        val first = SingBoxBridge(context)
        val second = SingBoxBridge(context)
        try {
            first.start(link)
            val priorPid = first.workerPid
            val secondPort = second.start(link)
            assertNotEquals("Replacement inherited native worker", priorPid, second.workerPid)
            // The stale controlling service may still be bound to the new
            // :singbox process. Its late STOP must not terminate this session.
            first.stop()
            assertTrue("Stale STOP killed the replacement worker",
                LocalSocks5Probe.check(secondPort).ready)
            assertTrue("Replacement worker lost its session",
                second.workerStatus().contains("движок запущен"))
        } finally {
            runCatching { first.stop() }
            second.stop()
        }
    }

}
