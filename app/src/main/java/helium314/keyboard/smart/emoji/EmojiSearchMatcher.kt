// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.emoji

/** Whole keyword matches first; incomplete queries fall back to word prefixes. */
object EmojiSearchMatcher {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    fun index(text: String) = " " + text.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
        .split(separators).filter { it.isNotEmpty() }.distinct().joinToString(" ") + " "

    fun <T> find(items: List<T>, query: String, keywords: (T) -> String): List<T> {
        val words = index(query).trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return items.take(80)
        val exact = items.asSequence().filter { item -> words.all { keywords(item).contains(" $it ") } }.take(80).toList()
        if (exact.isNotEmpty()) return exact
        return items.asSequence().filter { item -> words.all { keywords(item).contains(" $it") } }.take(80).toList()
    }
}
