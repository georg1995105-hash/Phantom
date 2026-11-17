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


    @Test fun capturesAbortReasonAndSamePidFatalWithoutOtherProcessOrCredentials() {
        val pid = 5434L
        val tid = 5440L
        val sig = text(2, "SIGABRT") + text(4, "SI_TKILL")
        val frame = scalar(1, 0x23a12c8) + text(6, "/private/base.apk")
        val thread = scalar(1, tid) + text(2, "Thread-4") +
            embedded(4, frame) + text(7, "unwind failed near JNI callback")
        val map = scalar(1, tid) + embedded(2, thread)

        fun logEntry(id: Long, line: String, level: Long = 7L): ByteArray =
            embedded(2, scalar(2, id) + scalar(3, tid) +
                scalar(4, level) + text(5, "libc") + text(6, line))

        val logs = logEntry(pid, "JNI DETECTED ERROR IN APPLICATION: use of stale JNI global ref") +
            logEntry(999L, "panic: THIS_MUST_NOT_APPEAR") +
            logEntry(pid, "user clicked https://test.example/private", 4L)

        val tombstone = scalar(5, pid) + scalar(6, tid) +
            embedded(10, sig) +
            text(14, "JNI DETECTED ERROR IN APPLICATION: password=EXPOSED_TOKEN; " +
                "tuic://username:secret@server.example.net /data/app/private/config") +
            embedded(16, map) + embedded(18, text(1, "crash") + logs)
        val result = NativeCrashTrace.summarize(tombstone)
        assertTrue(result, result.contains("SIGABRT"))
        assertTrue(result, result.contains("Abort message: JNI DETECTED ERROR"))
        assertTrue(result, result.contains("JNI global ref"))
        assertTrue(result, result.contains("Thread-4"))
        assertTrue(result, result.contains("0x23a12c8"))
        assertTrue(result, result.contains("unwind failed"))
        assertTrue(result, result.contains("[ссылка скрыта]"))
        assertFalse(result, result.contains("EXPOSED_TOKEN"))
        assertFalse(result, result.contains("server.example.net"))
        assertFalse(result, result.contains("/data/app"))
        assertFalse(result, result.contains("THIS_MUST_NOT_APPEAR"))
        assertFalse(result, result.contains("user clicked"))
    }

    @Test fun abortMessageAbsenceIsExplicit() {
        val sig = text(2, "SIGABRT")
        val result = NativeCrashTrace.summarize(scalar(6, 12) + embedded(10, sig))
        assertTrue(result, result.contains("Abort message: отсутствует"))
    }

    @Test fun missingThreadIsExplicitAndDoesNotLeakMemory() {
        val signal = text(2, "SIGABRT")
        val actual = NativeCrashTrace.summarize(scalar(6, 1234) + embedded(10, signal))
        assertTrue(actual, actual.contains("SIGABRT"))
        assertTrue(actual, actual.contains("отсутствует"))
    }
}
