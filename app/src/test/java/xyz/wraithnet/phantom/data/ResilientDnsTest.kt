package xyz.wraithnet.phantom.data

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class ResilientDnsTest {
    private val address = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

    private fun dns(block: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = block(hostname)
    }

    @Test fun systemResolverIsPreferred() {
        val fallbackCalls = AtomicInteger()
        val resolver = ResilientDns(
            systemDns = dns { listOf(address) },
            encryptedFallbacks = listOf(dns {
                fallbackCalls.incrementAndGet()
                listOf(address)
            }),
            allowFallback = { true }
        )
        assertEquals(listOf(address), resolver.lookup("example.org"))
        assertEquals(0, fallbackCalls.get())
    }

    @Test fun publicDnsFallbackOnSystemFailureOnlyWhenEnabled() {
        val resolver = ResilientDns(
            systemDns = dns { throw UnknownHostException("system failed") },
            encryptedFallbacks = listOf(dns { listOf(address) }),
            allowFallback = { true }
        )
        assertEquals(listOf(address), resolver.lookup("api.github.com"))
        val disabled = ResilientDns(
            systemDns = dns { throw UnknownHostException("system failed") },
            encryptedFallbacks = listOf(dns { listOf(address) }),
            allowFallback = { false }
        )
        assertThrows(UnknownHostException::class.java) { disabled.lookup("example.org") }
    }

    @Test fun internalNamesNeverGoToExternalResolver() {
        val fallbackCalls = AtomicInteger()
        val resolver = ResilientDns(
            systemDns = dns { throw UnknownHostException(it) },
            encryptedFallbacks = listOf(dns {
                fallbackCalls.incrementAndGet()
                listOf(address)
            }),
            allowFallback = { true }
        )
        for (host in listOf("localhost", "router.local", "lan", "private.lan",
                "a.internal", "10.0.0.1", "example.test")) {
            assertThrows(UnknownHostException::class.java) { resolver.lookup(host) }
        }
        assertEquals(0, fallbackCalls.get())
    }

    @Test fun fallsThroughFromFirstFailedProviderToSecond() {
        val count = AtomicInteger()
        val resolver = ResilientDns(
            systemDns = dns { throw UnknownHostException(it) },
            encryptedFallbacks = listOf(
                dns { count.incrementAndGet(); throw UnknownHostException("first") },
                dns { count.incrementAndGet(); listOf(address) }
            ),
            allowFallback = { true }
        )
        assertEquals(listOf(address), resolver.lookup("sub.example.com"))
        assertEquals(2, count.get())
    }

    @Test fun exhaustedFallbackKeepsOriginalError() {
        val resolver = ResilientDns(
            systemDns = dns { throw UnknownHostException("original") },
            encryptedFallbacks = listOf(dns { throw UnknownHostException("fallback") }),
            allowFallback = { true }
        )
        val error = assertThrows(UnknownHostException::class.java) {
            resolver.lookup("sub.example.com")
        }
        assertEquals("original", error.message)
        assertEquals(1, error.suppressed.size)
    }
}
