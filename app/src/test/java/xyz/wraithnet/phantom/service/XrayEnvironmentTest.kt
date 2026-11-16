package xyz.wraithnet.phantom.service

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.security.SecureRandom

class XrayEnvironmentTest {
    @Test fun xudpBaseKeyUsesExactly32DecodedBytes() {
        val data = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        assertTrue(XrayEnvironment.isValidKey(encoded))
        assertEquals(32, Base64.getUrlDecoder().decode(encoded).size)
        assertFalse(XrayEnvironment.isValidKey("phantom-xyz.wraithnet.phantom"))
        assertFalse(XrayEnvironment.isValidKey("not-a-key"))
    }
}
