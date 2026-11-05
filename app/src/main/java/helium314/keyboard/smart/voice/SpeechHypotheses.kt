// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

/** Preserve engine ordering unless aligned, valid confidence scores give a clear advantage. */
object SpeechHypotheses {
    fun choose(candidates: List<String>, confidence: FloatArray?): String {
        val first = candidates.indexOfFirst { it.isNotBlank() }
        if (first < 0) return ""
        if (confidence == null || confidence.size != candidates.size ||
            confidence.any { !it.isFinite() || it !in 0f..1f }) return candidates[first].trim()
        val best = candidates.indices.filter { candidates[it].isNotBlank() }.maxBy { confidence[it] }
        return candidates[if (confidence[best] > confidence[first] + 0.15f) best else first].trim()
    }
}
