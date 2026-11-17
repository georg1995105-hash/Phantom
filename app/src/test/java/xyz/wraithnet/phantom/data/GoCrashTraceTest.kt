package xyz.wraithnet.phantom.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoCrashTraceTest {
    @Test fun extractsFatalCauseWithoutSecretsOrFullPaths() {
        val raw = """
            startup log
            fatal error: runtime: stack split at bad time
            goroutine 73 [running]:
            runtime.cgocallbackg1(0xdeadbeef, 0x123)
                /usr/local/go/src/runtime/cgocall.go:420 +0x228
            github.com/sagernet/sing-box/common.(*Listener).OnChange(0x123)
                /private/build/project/listener.go:51 +0x10
            panic: do not include password=SECRET_VALUE at https://vpn.example.com/token
            goroutine 88 [sleep]:
            runtime.goexit({})
        """.trimIndent()
        val report = GoCrashTrace.summarize(raw)
        assertTrue(report, report.contains("stack split at bad time"))
        assertTrue(report, report.contains("runtime.cgocallbackg1"))
        assertTrue(report, report.contains("cgocall.go:420"))
        assertTrue(report, report.contains("listener.go:51"))
        assertFalse(report, report.contains("SECRET_VALUE"))
        assertFalse(report, report.contains("vpn.example.com"))
        assertFalse(report, report.contains("/private/"))
        assertFalse(report, report.contains("/usr/local/"))
        assertFalse(report, report.contains("0xdeadbeef"))
    }

    @Test fun ignoresNonfatalAndEmptyLogs() {
        assertTrue(GoCrashTrace.summarize("").isBlank())
        assertTrue(GoCrashTrace.summarize("All services initialized.\nOK").isBlank())
    }
}
