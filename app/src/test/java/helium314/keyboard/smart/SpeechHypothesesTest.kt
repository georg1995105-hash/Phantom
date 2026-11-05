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
}
