package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class AppRoutingTransferTest {
    @Test fun preservesModeAndPackagesInRoundtrip() {
        val original = setOf("com.openai.chatgpt", "org.mozilla.firefox")
        val result = AppRoutingTransfer.parse(AppRoutingTransfer.export(AppRouting.EXCLUDE, original))
        assertEquals(AppRouting.EXCLUDE, result.mode)
        assertEquals(original, result.packages)
    }

    @Test fun readsPlainListWithoutChangingMode() {
        val data = AppRoutingTransfer.parse("com.example.alpha\\norg.example.beta")
        assertNull(data.mode)
        assertEquals(2, data.packages.size)
    }

    @Test fun rejectsOversizedAndNonsense() {
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingTransfer.parse("garbage")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppRoutingTransfer.parse("a".repeat(100001))
        }
    }
}
