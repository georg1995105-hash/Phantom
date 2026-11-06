// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import java.util.Locale

/** Explicit user terms only. No recordings or full transcripts are retained. */
object VoiceVocabulary {
    const val MAX_INPUT = 8000
    private const val MAX_TERMS = 100
    private const val MAX_TERM_LENGTH = 80

    fun parse(input: String): List<String> = input.take(MAX_INPUT).split('\n', ';')
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length in 2..MAX_TERM_LENGTH && it.any(Char::isLetter) && it.none(Char::isISOControl) }
        .distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_TERMS)

    /** Only passed to createOnDeviceSpeechRecognizer; never to a potentially remote provider. */
    fun onDeviceHints(terms: List<String>, previous: CharSequence): ArrayList<String> {
        val context = Regex("[\\p{L}][\\p{L}\\p{N}+#.-]{1,39}")
            .findAll(previous.takeLast(240)).map { it.value }.toList().takeLast(16)
        return ArrayList((terms + context).distinctBy { it.lowercase(Locale.ROOT) }.take(120))
    }
}
