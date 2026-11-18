package xyz.wraithnet.phantom.data

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

/** RFC 9000 §§6.3, 17.2.1: one reserved-version packet, no credentials or VPN session. */
internal object QuicRtt {
    private val random = SecureRandom()
    private const val reserved = 0x0a0a0a0a

    fun measure(network: Network?, address: InetAddress, port: Int, timeout: Int): Boolean {
        val destination = ByteArray(12).also(random::nextBytes)
        val source = ByteArray(12).also(random::nextBytes)
        val request = ByteBuffer.allocate(1200).apply {
            put(0xc0.toByte()).putInt(reserved)
            put(destination.size.toByte()).put(destination)
            put(source.size.toByte()).put(source)
        }.array()
        return DatagramSocket(null).use { socket ->
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port))
            socket.soTimeout = timeout
            socket.send(DatagramPacket(request, request.size))
            val response = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(response)
            matches(response.data, response.length, destination, source)
        }
    }

    internal fun matches(bytes: ByteArray, size: Int, destination: ByteArray, source: ByteArray): Boolean {
        if (size !in (11 + destination.size + source.size)..bytes.size) return false
        val reply = ByteBuffer.wrap(bytes, 0, size)
        if (reply.get().toInt() and 0x80 == 0 || reply.int != 0) return false
        for (expected in listOf(source, destination)) {
            if ((reply.get().toInt() and 255) != expected.size || expected.any { reply.get() != it }) return false
        }
        if (reply.remaining() % 4 != 0) return false
        var supported = false
        while (reply.hasRemaining()) {
            when (reply.int) {
                reserved, 0 -> return false
                1, 0x6b3343cf -> supported = true
            }
        }
        return supported
    }
}
