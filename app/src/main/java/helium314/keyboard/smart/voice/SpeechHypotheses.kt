// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import java.util.Locale

/** Preserve engine ordering unless aligned, valid confidence scores give a clear advantage. */
object SpeechHypotheses {
    fun choose(candidates: List<String>, confidence: FloatArray?, formattedPair: Boolean = false,
               personalTerms: List<String> = emptyList()): String {
        val first = candidates.indexOfFirst { it.isNotBlank() }
        if (first < 0) return ""
        // Android API 33 formatting returns [formatted, raw], not two ASR alternatives.
        if (formattedPair && first == 0 && candidates.size == 2) return candidates[0].trim()
        if (confidence == null || confidence.size != candidates.size ||
            confidence.any { !it.isFinite() || it !in 0f..1f }) return candidates[first].trim()
        val best = candidates.indices.filter { candidates[it].isNotBlank() }.maxBy { confidence[it] }
        val selected = if (confidence[best] > confidence[first] + 0.15f) best else first
        if (selected != first || personalTerms.isEmpty()) return candidates[selected].trim()
        val terms = personalTerms.map { it.lowercase(Locale.ROOT) }.toSet()
        val original = words(candidates[first])
        // A personal term can break a close tie only when the engine itself scored it higher.
        // Choose an existing hypothesis; never manufacture or substitute a word or a number.
        val personal = candidates.indices.filter { index ->
            if (index == first || confidence[index] < .8f || confidence[index] < confidence[first] + .03f) false
            else {
                val alternative = words(candidates[index])
                val changed = original.indices.filter { original[it] != alternative.getOrNull(it) }
                original.size == alternative.size && changed.size == 1 && changed.all {
                    val old = original[it]; val new = alternative[it]
                    new in terms && old.none(Char::isDigit) && new.none(Char::isDigit) && nearby(old, new)
                }
            }
        }.maxByOrNull { confidence[it] }
        return candidates[personal ?: first].trim()
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}+#.-]+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value.trim('.') }.toList()

    private fun nearby(a: String, b: String): Boolean {
        if (a.length < 3 || b.length < 3 || kotlin.math.abs(a.length - b.length) > 1) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1); row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = row
        }
        return previous[b.length] <= 1
    }
}
