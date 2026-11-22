package xyz.wraithnet.phantom.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvSubscriptionPairingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun qrContainsOnlyEphemeralPairingDataNotSubscriptionSecrets() = runBlocking {
        val listener = TvSubscriptionPairing.listen(context)
        try {
            assertTrue(TvSubscriptionPairing.isPairingCode(listener.qrCode))
            assertTrue(listener.qrCode.startsWith("phantom://pair?"))
            assertFalse(listener.qrCode.contains("subscription"))
            assertFalse(listener.qrCode.contains("password"))
            assertFalse(TvSubscriptionPairing.isPairingCode("vless://example.org"))
            assertFalse(TvSubscriptionPairing.isPairingCode("https://example.org"))
        } finally {
            listener.close()
        }
    }

    @Test fun selectedSubscriptionIsEncryptedTransferredAndAcknowledgedAfterSave() = runBlocking {
        val listener = TvSubscriptionPairing.listen(context)
        val secret = "https://example.org/subscription?token=TOP_PRIVATE_PASSWORD"
        val title = "A private test subscription"
        var savedSource: String? = null
        var savedTitle: String? = null
        try {
            assertFalse(listener.qrCode.contains("TOP_PRIVATE_PASSWORD"))
            withTimeout(25_000L) {
                val receiving = async(Dispatchers.IO) {
                    listener.receive { source, name ->
                        savedSource = source
                        savedTitle = name
                        true
                    }
                }
                assertTrue(TvSubscriptionPairing.send(context, listener.qrCode, secret, title))
                assertTrue(receiving.await())
            }
            assertEquals(secret, savedSource)
            assertEquals(title, savedTitle)
        } finally {
            listener.close()
        }
    }

    @Test fun recipientCanRejectImportAndSenderDoesNotReportSuccess() = runBlocking {
        val listener = TvSubscriptionPairing.listen(context)
        try {
            withTimeout(25_000L) {
                val receiving = async(Dispatchers.IO) {
                    listener.receive { _, _ -> false }
                }
                assertFalse(TvSubscriptionPairing.send(context, listener.qrCode,
                    "https://example.org/subscription?key=fake", "test"))
                assertFalse(receiving.await())
            }
        } finally {
            listener.close()
        }
    }
}
