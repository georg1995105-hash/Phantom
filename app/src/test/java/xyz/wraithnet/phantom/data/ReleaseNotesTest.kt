package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesTest {
    @Test fun extractsCompactBulletsWithoutMarkdownHeadings() {
        val source = "# PHANTOM VPN 0.2.8\n\n- Настройки удобнее\n- Есть импорт\n\nОстальное"
        assertEquals("• Настройки удобнее\n• Есть импорт", ReleaseNotes.summary(source, 2))
    }

    @Test fun emptyReleaseTextDoesNotCreatePlaceholder() {
        assertEquals("", ReleaseNotes.summary(""))
    }
}
