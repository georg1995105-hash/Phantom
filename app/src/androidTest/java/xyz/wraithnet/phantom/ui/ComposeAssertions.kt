package xyz.wraithnet.phantom.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun ComposeTestRule.awaitVisible(
    matcher: SemanticsMatcher,
    timeoutMs: Long = 10_000,
): SemanticsNodeInteraction {
    waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
    return onNode(matcher).assertIsDisplayed()
}
