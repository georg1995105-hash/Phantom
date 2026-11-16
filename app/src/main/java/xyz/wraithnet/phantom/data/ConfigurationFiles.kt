package xyz.wraithnet.phantom.data

import java.io.Reader

/** Bounded UTF-8 import content. Never access file paths or unrestricted storage. */
object ConfigurationFiles {
    const val MAX_CHARS = 500_000

    fun read(reader: Reader, maxChars: Int = MAX_CHARS): String {
        require(maxChars > 0)
        val output = StringBuilder()
        val block = CharArray(4096)
        while (true) {
            val n = reader.read(block)
            if (n < 0) break
            if (n == 0) continue
            require(output.length.toLong() + n <= maxChars) { "Файл конфигурации слишком большой" }
            output.append(block, 0, n)
        }
        val content = output.toString().removePrefix("\uFEFF").trim()
        require(content.isNotBlank()) { "Файл конфигурации пуст" }
        return content
    }
}
