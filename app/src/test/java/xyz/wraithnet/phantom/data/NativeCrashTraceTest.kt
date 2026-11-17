package xyz.wraithnet.phantom.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class NativeCrashTraceTest {
    private fun varint(number: Long): ByteArray {
        var n = number
        val bytes = ByteArrayOutputStream()
        while (n >= 128) {
            bytes.write((n.toInt() and 127) or 128)
            n = n ushr 7
        }
        bytes.write(n.toInt())
        return bytes.toByteArray()
    }

    private fun scalar(field: Int, value: Long): ByteArray =
        varint((field * 8).toLong()) + varint(value)

    private fun embedded(field: Int, bytes: ByteArray): ByteArray =
        varint((field * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes

    private fun text(field: Int, value: String): ByteArray =
        embedded(field, value.toByteArray(Charsets.UTF_8))

    @Test fun extractsOnlyCrashingThreadSignalAndFrame() {
        val sig = text(2, "SIGSEGV") + text(4, "SEGV_MAPERR")
        val frame = scalar(1, 0x1234) +
            text(4, "runtime.cgocallback") +
            text(6, "/data/app/private/data/libbox.so")
        val thread = scalar(1, 321) + text(2, "signal handler") + embedded(4, frame)
        val map = scalar(1, 321) + embedded(2, thread)
        val other = scalar(1, 999) +
            embedded(2, text(2, "another thread") +
                embedded(4, text(4, "PRIVATE_TOKEN_SECRET")))
        val tombstone = scalar(6, 321) + embedded(10, sig) +
            embedded(16, map) + embedded(16, other) +
            text(14, "tuic://private:password@vpn.host") // never expose abort data
        val actual = NativeCrashTrace.summarize(tombstone)
        assertTrue(actual, actual.contains("SIGSEGV"))
        assertTrue(actual, actual.contains("SEGV_MAPERR"))
        assertTrue(actual, actual.contains("runtime.cgocallback"))
        assertTrue(actual, actual.contains("libbox.so"))
        assertTrue(actual, actual.contains("0x1234"))
        assertFalse(actual, actual.contains("/data/app/private"))
        assertFalse(actual, actual.contains("PRIVATE_TOKEN_SECRET"))
        assertFalse(actual, actual.contains("vpn.host"))
    }

    @Test fun missingThreadIsExplicitAndDoesNotLeakMemory() {
        val signal = text(2, "SIGABRT")
        val actual = NativeCrashTrace.summarize(scalar(6, 1234) + embedded(10, signal))
        assertTrue(actual, actual.contains("SIGABRT"))
        assertTrue(actual, actual.contains("отсутствует"))
    }
}
