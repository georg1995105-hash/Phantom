package xyz.wraithnet.phantom.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.wraithnet.phantom.data.TcpEndpoint
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object TcpPing {
    suspend fun measure(context: Context, endpoint: TcpEndpoint, timeout: Int): Long? =
        withContext(Dispatchers.IO) {
            val network = context.getSystemService(ConnectivityManager::class.java).physicalNetwork()
                ?: return@withContext null
            withTimeoutOrNull(timeout.toLong()) {
                val addresses = resolve(network, endpoint.host)
                if (addresses.isEmpty()) return@withTimeoutOrNull null
                // Prefer Android's first address but give the other family a prompt chance.
                val first = addresses.first()
                val (same, other) = addresses.drop(1).partition { (it is Inet4Address) == (first is Inet4Address) }
                val order = listOf(first) + other.take(1) + same + other.drop(1)
                coroutineScope {
                    val results = Channel<Long?>(order.size)
                    val jobs = order.mapIndexed { index, address ->
                        launch {
                            if (index > 0) delay(200L * index)
                            results.send(connect(network, InetSocketAddress(address, endpoint.port), timeout))
                        }
                    }
                    try {
                        repeat(order.size) { results.receive()?.let { return@coroutineScope it } }
                        null
                    } finally {
                        jobs.forEach { it.cancel() }
                    }
                }
            }
        }

    // getAllByName can block beyond the requested deadline on older Android DNS resolvers.
    private suspend fun resolve(network: Network, host: String): List<InetAddress> =
        suspendCancellableCoroutine { result ->
            Dispatchers.IO.asExecutor().execute {
                if (result.isActive) {
                    val addresses = try { network.getAllByName(host).distinct() }
                        catch (_: Exception) { emptyList() }
                    result.resume(addresses)
                }
            }
        }

    private suspend fun connect(network: Network, address: InetSocketAddress, timeout: Int): Long? =
        suspendCancellableCoroutine { result ->
            // The socket and the resolver must refer to the very same Android Network.
            val delayMs = try {
                network.socketFactory.createSocket().use { socket ->
                    result.invokeOnCancellation { runCatching { socket.close() } }
                    val start = System.nanoTime()
                    socket.connect(address, timeout)
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                }
            } catch (_: Exception) {
                null
            }
            result.resume(delayMs)
        }
}
