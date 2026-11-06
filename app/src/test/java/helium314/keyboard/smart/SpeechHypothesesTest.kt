// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import helium314.keyboard.smart.voice.SpeechHypotheses
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechHypothesesTest {
    @Test fun preservesEngineOrderWithoutReliableScores() {
        val texts = listOf("Первая фраза.", "первая фраза")
        for (scores in listOf(null, floatArrayOf(-1f, -1f), floatArrayOf(.2f), floatArrayOf(Float.NaN, .8f)))
            assertEquals(texts[0], SpeechHypotheses.choose(texts, scores))
    }
    @Test fun selectsOnlyClearConfidenceAdvantage() {
        assertEquals("Иван", SpeechHypotheses.choose(listOf("диван", "Иван"), floatArrayOf(.3f, .8f)))
        assertEquals("Первый", SpeechHypotheses.choose(listOf("Первый", "Второй"), floatArrayOf(.7f, .8f)))
    }
    @Test fun handlesMissingAndBlankResults() {
        assertEquals("", SpeechHypotheses.choose(emptyList(), null))
        assertEquals("текст", SpeechHypotheses.choose(listOf(" ", " текст "), null))
    }
    @Test fun formattingPairIsNotRankedAsTwoIndependentHypotheses() {
        assertEquals("Встреча в 25-м офисе.", SpeechHypotheses.choose(
            listOf("Встреча в 25-м офисе.", "встреча в двадцать пятом офисе"),
            floatArrayOf(.1f, .95f), formattedPair = true))
    }
    @Test fun explicitTermCanResolveOneCloseWordWhenEngineAlsoPrefersIt() {
        assertEquals("Открой Котлин", SpeechHypotheses.choose(
            listOf("Открой Котлинн", "Открой Котлин"), floatArrayOf(.82f, .87f), personalTerms = listOf("Котлин")))
        assertEquals("Открой Котлинн", SpeechHypotheses.choose(
            listOf("Открой Котлинн", "Открой Котлин"), floatArrayOf(.87f, .82f), personalTerms = listOf("Котлин")))
    }
    @Test fun dictionaryDoesNotRewriteNumbersOrDistantMeanings() {
        assertEquals("Встреча 15 мая", SpeechHypotheses.choose(
            listOf("Встреча 15 мая", "Встреча 50 мая"), floatArrayOf(.8f, .85f), personalTerms = listOf("50")))
        assertEquals("Купи хлеб", SpeechHypotheses.choose(
            listOf("Купи хлеб", "Позвони Игорю"), floatArrayOf(.8f, .85f), personalTerms = listOf("Игорю")))
    }
}
