package xyz.wraithnet.phantom.data

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * System DNS first. Public DoH only as a bounded fallback when the OS reports
 * UnknownHostException; never overrides private/local names or a disabled policy.
 * DoH providers use their own TLS-verified hostnames (no insecure HTTP or cert bypass).
 */
class ResilientDns(
    private val systemDns: Dns,
    private val encryptedFallbacks: List<Dns>,
    private val allowFallback: () -> Boolean,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        try {
            return systemDns.lookup(hostname)
        } catch (original: UnknownHostException) {
            if (!allowFallback() || !isPublicHostname(hostname)) throw original
            for (dns in encryptedFallbacks) {
                try {
                    val addresses = dns.lookup(hostname)
                    if (addresses.isNotEmpty()) return addresses
                } catch (e: Exception) {
                    // A provider can be unreachable; try the next one without
                    // overriding the original system failure's diagnostic.
                    original.addSuppressed(e)
                }
            }
            throw original
        }
    }

    companion object {
        fun isPublicHostname(host: String): Boolean {
            if (!host.contains('.') || host.length > 253) return false
            if (host.endsWith(".local", true) || host.endsWith(".lan", true) ||
                host.endsWith(".internal", true) || host.endsWith(".localhost", true) ||
                host.endsWith(".test", true) || host.endsWith(".invalid", true)) return false
            if (host.all { it.isDigit() || it == '.' || it == ':' } ) return false
            return host.matches(Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+"))
        }
    }
}
