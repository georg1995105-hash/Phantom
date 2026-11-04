// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomRowOrderTest {
    @Test fun defaultOrderIsPredictable() =
        assertEquals(listOf("emoji", "language_switch", "numpad"),
            BottomRowOrder.sort(listOf("language_switch", "numpad", "emoji"), null))

    @Test fun hiddenControlsStayRecoverable() =
        assertEquals(listOf("language_switch", "numpad"),
            BottomRowOrder.sort(listOf("numpad", "language_switch"), "emoji|language_switch|numpad"))

    @Test fun movingDoesNotLoseHiddenKeys() =
        assertEquals("language_switch|emoji|numpad",
            BottomRowOrder.move("emoji|language_switch|numpad", "language_switch", "emoji"))

    @Test fun malformedPreferenceDoesNotCrash() =
        assertEquals("emoji|language_switch|numpad",
            BottomRowOrder.move("emoji|garbage", "emoji", "missing"))
}
