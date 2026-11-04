// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small on-device store of words the user actually commits (typed, gestured, picked). It is used
 * to rank gesture candidates and to accept personal words that are missing from the main
 * dictionary. Never filled from password / no-learning fields (callers check incognito state).
 */
object SmartLearning {
    private const val TAG = "SmartLearning"
    private const val FILE_NAME = "smart_learning.tsv"
    private const val MAX_WORDS = 6000

    private val counts = ConcurrentHashMap<String, Int>()
    private var file: File? = null
    private val writer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var dirty = false
    @Volatile var version = 0
        private set

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        writer.execute {
            try {
                if (f.isFile) f.forEachLine { line ->
                    val tab = line.lastIndexOf('\t')
                    if (tab > 0) line.substring(tab + 1).toIntOrNull()?.let { counts[line.substring(0, tab)] = it }
                }
                version++
            } catch (e: Exception) {
                Log.w(TAG, "could not read learning file", e)
            }
        }
    }

    fun learn(word: String) {
        val w = word.trim()
        if (w.length < 2 || w.length > 32 || !w.any { it.isLetter() } || w.any { it.isWhitespace() }) return
        counts.merge(w, 1) { a, b -> a + b }
        version++
        scheduleSave()
    }

    fun forget(word: String) {
        if (counts.remove(word) != null || counts.remove(word.lowercase()) != null) {
            version++
            scheduleSave()
        }
    }

    fun clear() {
        counts.clear()
        version++
        writer.execute { file?.delete() }
    }

    fun count(word: String): Int = counts[word] ?: 0

    fun snapshot(): Map<String, Int> = HashMap(counts)

    fun size() = counts.size

    private fun scheduleSave() {
        if (dirty) return
        dirty = true
        writer.schedule({
            dirty = false
            save()
        }, 5, TimeUnit.SECONDS)
    }

    private fun save() {
        val f = file ?: return
        try {
            if (counts.size > MAX_WORDS) {
                counts.entries.sortedBy { it.value }.take(counts.size - MAX_WORDS).forEach { counts.remove(it.key) }
            }
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter().use { out -> counts.forEach { (w, c) -> out.write(w); out.write("\t"); out.write(c.toString()); out.write("\n") } }
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "could not save learning file", e)
        }
    }
}
