// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualTapPunctuationTest {
    @Test fun rapidSecondTapChangesPeriodToComma() {
        assertTrue(DualTapPunctuation.shouldReplace(1000, 1200, "Привет.", false))
        assertTrue(DualTapPunctuation.shouldReplace(1000, 1200, "Привет. ", false))
    }

    @Test fun slowOrUnrelatedTapDoesNotReplace() {
        assertFalse(DualTapPunctuation.shouldReplace(1000, 1500, "Привет.", false))
        assertFalse(DualTapPunctuation.shouldReplace(1000, 1200, "Привет!", false))
        assertFalse(DualTapPunctuation.shouldReplace(1000, 1200, "Привет.", true))
        assertFalse(DualTapPunctuation.shouldReplace(0, 1200, "Привет.", false))
    }
}
