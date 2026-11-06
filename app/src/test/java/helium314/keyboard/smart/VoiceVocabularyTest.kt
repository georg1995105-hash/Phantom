// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import helium314.keyboard.smart.voice.VoiceVocabulary
import org.junit.Assert.*
import org.junit.Test

class VoiceVocabularyTest {
    @Test fun termsAreExplicitBoundedAndDeduplicated() {
        assertEquals(listOf("Иван Петров", "Kotlin", "C++"), VoiceVocabulary.parse(" Иван   Петров\nKotlin;kotlin;C++;\n"))
        assertEquals(100, VoiceVocabulary.parse((1..200).joinToString(";") { "Термин$it" }).size)
        assertTrue(VoiceVocabulary.parse("a".repeat(81)).isEmpty())
    }
    @Test fun onlySmallRecentContextIsUsedForEphemeralLocalHints() {
        val hints = VoiceVocabulary.onDeviceHints(listOf("Kotlin"), "СтарыйКонтекст " + "слово ".repeat(80) + "последнее")
        assertTrue("Kotlin" in hints)
        assertTrue("последнее" in hints)
        assertFalse("СтарыйКонтекст" in hints)
        assertTrue(hints.size <= 120)
    }
}
