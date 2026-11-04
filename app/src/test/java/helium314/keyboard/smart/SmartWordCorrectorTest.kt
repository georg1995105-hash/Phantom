// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartWordCorrectorTest {
    @Test fun lastWordSupportsRussian() =
        assertEquals("ошибка", SmartWordCorrector.wordBeforeCursor("Это ошика ошибка"))

    @Test fun punctuationDoesNotSelectUnrelatedWord() =
        assertNull(SmartWordCorrector.wordBeforeCursor("Привет."))

    @Test fun hyphenatedWordIsOneWord() =
        assertEquals("по-русски", SmartWordCorrector.wordBeforeCursor("Напиши по-русски"))
}
