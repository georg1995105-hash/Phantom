// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.gesture

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale

/**
 * Word list with unigram probabilities for one locale, extracted once from the main binary
 * dictionary and cached in app storage, because iterating the native dictionary takes seconds.
 */
class GestureLexicon(val locale: Locale, val words: Array<String>, val probabilities: IntArray) {

    companion object {
        private const val TAG = "GestureLexicon"
        private const val MAGIC = 0x53564b4c // "SVKL"
        private const val VERSION = 2
        private const val MAX_WORDS = 160_000

        fun load(context: Context, locale: Locale): GestureLexicon? {
            val dictFiles = mainDictionaryFiles(context, locale)
            if (dictFiles.isEmpty()) return null
            val signature = dictFiles.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
            val cacheDir = File(context.filesDir, "gesture_lexicon").apply { mkdirs() }
            val cacheFile = File(cacheDir, locale.toLanguageTag() + ".bin")
            readCache(cacheFile, signature, locale)?.let { return it }
            val start = System.currentTimeMillis()
            val map = HashMap<String, Int>(200_000)
            for (file in dictFiles) {
                var dict: BinaryDictionary? = null
                try {
                    dict = BinaryDictionary(file.absolutePath, 0, file.length(), false, locale, "main", false)
                    if (!dict.isValidDictionary) continue
                    dict.forEachWord { word, probability ->
                        if (probability >= 0 && word.length in 2..32 && word.any { it.isLetter() }) {
                            val old = map[word]
                            if (old == null || old < probability) map[word] = probability
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "could not read ${file.name}", e)
                } finally {
                    dict?.close()
                }
            }
            if (map.isEmpty()) return null
            val sorted = map.entries.sortedByDescending { it.value }.take(MAX_WORDS)
            val lexicon = GestureLexicon(locale, Array(sorted.size) { sorted[it].key }, IntArray(sorted.size) { sorted[it].value })
            Log.i(TAG, "built lexicon for $locale: ${lexicon.words.size} words in ${System.currentTimeMillis() - start} ms")
            writeCache(cacheFile, signature, lexicon)
            return lexicon
        }

        private fun mainDictionaryFiles(context: Context, locale: Locale): List<File> {
            val (cached, assets) = DictionaryFactory.getAvailableDictsForLocale(locale, context, false)
            val files = cached.filter { it.name.startsWith("main") }.toMutableList()
            if (files.isEmpty()) {
                assets.filter { it.startsWith("main") }.forEach { name ->
                    DictionaryInfoUtils.extractAssetsDictionary(name, locale, context)?.let { files.add(it) }
                }
            }
            return files
        }

        private fun readCache(file: File, signature: String, locale: Locale): GestureLexicon? {
            if (!file.isFile) return null
            return try {
                DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                    if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readUTF() != signature) return null
                    val count = input.readInt()
                    val words = Array(count) { "" }
                    val probabilities = IntArray(count)
                    for (i in 0 until count) {
                        words[i] = input.readUTF()
                        probabilities[i] = input.readUnsignedByte()
                    }
                    GestureLexicon(locale, words, probabilities)
                }
            } catch (e: Exception) {
                Log.w(TAG, "lexicon cache unreadable", e)
                null
            }
        }

        private fun writeCache(file: File, signature: String, lexicon: GestureLexicon) {
            try {
                val tmp = File(file.path + ".tmp")
                DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    out.writeUTF(signature)
                    out.writeInt(lexicon.words.size)
                    for (i in lexicon.words.indices) {
                        out.writeUTF(lexicon.words[i])
                        out.writeByte(lexicon.probabilities[i].coerceIn(0, 255))
                    }
                }
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "could not write lexicon cache", e)
            }
        }
    }
}
