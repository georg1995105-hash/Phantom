// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

/**
 * Stable order for optional shortcut keys. Saved preferences may omit controls when
 * they have been temporarily hidden, but all controls must remain recoverable.
 */
object BottomRowOrder {
    private val controls = listOf("emoji", "language_switch", "numpad")

    fun sort(visible: List<String>, saved: String?): List<String> {
        val preference = saved?.split('|').orEmpty().filter { it in controls }.distinct()
        val order = (preference + controls.filterNot { it in preference })
        return order.filter { it in visible }
    }

    fun move(saved: String?, from: String, before: String): String {
        val order = (saved?.split('|').orEmpty().filter { it in controls }.distinct() +
            controls.filterNot { it in (saved?.split('|').orEmpty()) }).toMutableList()
        if (from !in controls || before !in controls || from == before) return order.joinToString("|")
        order.remove(from)
        val index = order.indexOf(before)
        order.add(if (index < 0) order.size else index, from)
        return order.joinToString("|")
    }
}
