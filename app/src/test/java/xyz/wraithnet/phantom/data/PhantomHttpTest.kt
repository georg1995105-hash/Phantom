package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.UnknownHostException

class PhantomHttpTest {
    @Test fun detectsExternalVpnWithoutRevealingSubscriptionLink() {
        val state = PhantomHttp.NetworkState(routeId = "42", validated = true, otherVpn = true)
        val message = PhantomHttp.explainFailure(UnknownHostException("secret.example"), state)
        assertTrue(message.contains("другой VPN"))
        assertFalse(message.contains("secret.example"))
        assertTrue(message.contains("Отключите"))
    }

    @Test fun ordinaryDnsFailureExplainsDnsNotDeviceBrand() {
        val state = PhantomHttp.NetworkState(routeId = "42", validated = true, otherVpn = false)
        val message = PhantomHttp.explainFailure(UnknownHostException("secret"), state)
        assertTrue(message.contains("DNS"))
        assertFalse(message.contains("secret"))
    }

    @Test fun downloadReadIsBoundedAndHandlesUnicode() {
        val text = "Нидерланды — Швеция"
        assertEquals(text, PhantomHttp.readUtf8Limited(
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), 512))
        assertThrows(IllegalArgumentException::class.java) {
            PhantomHttp.readUtf8Limited(ByteArrayInputStream(ByteArray(17)), 16)
        }
    }

    @Test fun unknownFailureReturnsSafeErrorWithoutLeakingUrl() {
        val message = PhantomHttp.describe(IOException("https://vpn.example/token"), true)
        assertEquals("Ошибка сети. Проверьте подключение и повторите попытку.", message)
    }
}
