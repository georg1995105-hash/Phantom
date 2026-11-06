// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import helium314.keyboard.smart.emoji.EmojiSearchMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiSearchMatcherTest {
    private val entries = listOf("кот кошка улыбка", "дискотека танец", "рыбная котлета", "котенок")
        .map { it to EmojiSearchMatcher.index(it) }
    @Test fun catQueryDoesNotMatchDiscothequeOrFishCake() {
        assertEquals(listOf(entries[0]), EmojiSearchMatcher.find(entries, "КОТ") { it.second })
        assertEquals(listOf(entries[3]), EmojiSearchMatcher.find(entries, "котёнок") { it.second })
    }
    @Test fun incompleteMultiwordQueryUsesKeywordPrefixes() {
        assertEquals(listOf(entries[0]), EmojiSearchMatcher.find(entries, "кош улы") { it.second })
        assertEquals(emptyList<Pair<String, String>>(), EmojiSearchMatcher.find(entries, "танец кот") { it.second })
    }
}
