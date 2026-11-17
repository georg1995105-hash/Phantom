package xyz.wraithnet.phantom.service

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.concurrent.TimeUnit

/**
 * Authenticated, certificate-verified HTTPS roundtrip through local SOCKS5.
 *
 * A listening SOCKS port only proves that libbox started. This probe verifies
 * the real TUIC/AnyTLS upstream without exposing its credentials in diagnostics.
 * SOCKS5 DOMAIN targets avoid dependency on the device's DNS resolver.
 */
internal object SocksHttpsProbe {
    private val targets = listOf(
        "www.gstatic.com" to "/generate_204",
        "cp.cloudflare.com" to "/generate_204"
    )

    fun measure(port: Int): Long {
        require(port in 1024..65535) { "Неверный SOCKS-порт" }
        for ((host, path) in targets) {
            val delay = runCatching { request(port, host, path) }.getOrDefault(-1L)
            if (delay > 0L) return delay
        }
        return -1L
    }

    private fun request(port: Int, host: String, path: String): Long {
        val start = System.nanoTime()
        Socket().use { socket ->
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress("127.0.0.1", port), 2500)
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            // SOCKS5: no authentication; credentials apply to remote VPN, not this loopback.
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            val greeting = readExact(input, 2)
            require(greeting[0] == 5.toByte() && greeting[1] == 0.toByte()) {
                "SOCKS5 не согласовал подключение"
            }
            val name = host.toByteArray(StandardCharsets.US_ASCII)
            require(name.size in 1..255)
            output.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()))
            output.write(name)
            output.write(byteArrayOf(1, -69)) // HTTPS 443
            output.flush()
            val response = readExact(input, 4)
            require(response[0] == 5.toByte() && response[1] == 0.toByte()) {
                "Сервер отказал в SOCKS5 CONNECT"
            }
            when (response[3].toInt() and 255) {
                1 -> readExact(input, 4)
                3 -> readExact(input, readExact(input, 1)[0].toInt() and 255)
                4 -> readExact(input, 16)
                else -> error("Неверный SOCKS5 ответ")
            }
            readExact(input, 2) // BND port
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, host, 443, false) as SSLSocket
            ssl.use {
                it.soTimeout = 5000
                it.sslParameters = it.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(host))
                }
                it.startHandshake()
                it.outputStream.write(
                    "GET $path HTTP/1.1\\r\\nHost: $host\\r\\nConnection: close\\r\\n\\r\\n"
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                it.outputStream.flush()
                val first = ByteArray(256)
                var count = 0
                while (count < first.size) {
                    val next = it.inputStream.read()
                    if (next < 0) break
                    first[count++] = next.toByte()
                    if (count >= 2 && first[count - 2] == 13.toByte() &&
                        first[count - 1] == 10.toByte()) break
                }
                val status = String(first, 0, count, StandardCharsets.US_ASCII)
                require(status.startsWith("HTTP/1.1 204") ||
                    status.startsWith("HTTP/1.0 204")) {
                    "HTTPS-проверка вернула неожиданный статус"
                }
            }
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1)
    }

    private fun readExact(input: java.io.InputStream, length: Int): ByteArray {
        require(length in 0..512)
        val bytes = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(bytes, at, length - at)
            check(n > 0) { "SOCKS5 соединение закрыто" }
            at += n
        }
        return bytes
    }
}
