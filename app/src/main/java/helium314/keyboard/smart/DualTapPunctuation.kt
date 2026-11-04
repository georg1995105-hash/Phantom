// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

/** Interprets the second fast tap of the combined .,/ key. No Android dependency. */
object DualTapPunctuation {
    private const val DOUBLE_TAP_MS = 420L
    fun shouldReplace(previous: Long, now: Long, beforeCursor: String, selection: Boolean): Boolean =
        previous > 0 && now >= previous && now - previous <= DOUBLE_TAP_MS &&
            !selection && (beforeCursor.endsWith(".") || beforeCursor.endsWith(". "))
}
