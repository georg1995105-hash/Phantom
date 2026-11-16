package xyz.wraithnet.phantom.data

/** Compact user-facing text from GitHub Release Markdown. */
object ReleaseNotes {
    fun summary(markdown: String, maxItems: Int = 4): String {
        if (maxItems <= 0) return ""
        return markdown.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotBlank() }
            .take(maxItems)
            .joinToString("\n") { "• " + it }
    }
}
