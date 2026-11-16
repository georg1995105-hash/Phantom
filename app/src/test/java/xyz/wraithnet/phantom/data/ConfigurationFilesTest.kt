package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ConfigurationFilesTest {
    @Test fun utf8TextAndJsonAreReadWithoutChangingSecrets() {
        val raw = "vless://abc@server.example:443?security=reality"
        assertEquals(raw, ConfigurationFiles.read(StringReader(raw)))
        assertEquals("{\"outbounds\":[]}", ConfigurationFiles.read(
            StringReader("{\"outbounds\":[]}")))
    }

    @Test fun rejectsEmptyAndOversizedFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationFiles.read(StringReader("    "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationFiles.read(StringReader("123456"), 5)
        }
    }

    @Test fun handlesUtf8Bom() {
        assertEquals("vless://hello", ConfigurationFiles.read(StringReader("\uFEFFvless://hello")))
    }
}
