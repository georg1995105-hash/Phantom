// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.gesture

import android.content.Context
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SuggestionResults
import helium314.keyboard.smart.SmartLearning
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.min

/** Glue between HeliBoard's batch input pipeline and [GestureDecoder]. */
object SmartGesture {
    private const val TAG = "SmartGesture"
    private val loader = Executors.newSingleThreadExecutor()
    private val lexicons = ConcurrentHashMap<Locale, GestureLexicon>()
    private val loading = ConcurrentHashMap.newKeySet<Locale>()

    private class IndexCache(val locale: Locale, val layoutSignature: String, val learningVersion: Int, val index: GestureDecoder.WordIndex)
    @Volatile private var indexCache: IndexCache? = null

    val GESTURE_DICTIONARY = Dictionary.PhonyDictionary(Dictionary.TYPE_MAIN)

    @Volatile private var appContext: Context? = null

    fun prepare(context: Context, locale: Locale) {
        val app = context.applicationContext
        appContext = app
        SmartLearning.init(app)
        if (lexicons.containsKey(locale) || !loading.add(locale)) return
        loader.execute {
            try {
                GestureLexicon.load(app, locale)?.let { lexicons[locale] = it }
            } catch (e: Throwable) {
                Log.e(TAG, "lexicon load failed for $locale", e)
            } finally {
                loading.remove(locale)
            }
        }
    }

    fun isReady(locale: Locale) = lexicons.containsKey(locale)

    fun getSuggestionResults(
        composedData: ComposedData, ngramContext: NgramContext, keyboard: Keyboard,
        locale: Locale, nextWordPredictions: Collection<SuggestedWordInfo>
    ): SuggestionResults {
        val results = SuggestionResults(SuggestedWords.MAX_SUGGESTIONS, ngramContext.isBeginningOfSentenceContext, false)
        val lexicon = lexicons[locale]
        if (lexicon == null) {
            appContext?.let { prepare(it, locale) }
            return results
        }
        val layout = buildLayout(keyboard) ?: return results
        val index = getIndex(lexicon, layout)

        val pointers = composedData.mInputPointers
        val n = pointers.pointerSize
        if (n < 2) return results
        val view = KeyboardSwitcher.getInstance().mainKeyboardView
        val keyW = keyboard.mMostCommonKeyWidth.toFloat().coerceAtLeast(1f)
        val keyH = keyboard.mMostCommonKeyHeight.toFloat().coerceAtLeast(1f)
        val rawX = pointers.xCoordinates
        val rawY = pointers.yCoordinates
        val ids = pointers.pointerIds
        val firstId = ids[0]
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var count = 0
        for (i in 0 until n) {
            if (ids[i] != firstId) continue
            val x = view?.getKeyX(rawX[i]) ?: rawX[i]
            val y = view?.getKeyY(rawY[i]) ?: rawY[i]
            xs[count] = x / keyW
            ys[count] = y / keyH
            count++
        }

        val boost = HashMap<String, Float>()
        var rank = 0
        for (info in nextWordPredictions) {
            if (rank >= 12) break
            boost[info.mWord] = (boost[info.mWord] ?: 0f) + 0.35f * (1f - rank / 14f)
            rank++
        }
        val decoder = GestureDecoder(layout, index)
        val candidates = decoder.decode(xs, ys, count, boost, SuggestedWords.MAX_SUGGESTIONS)
        for ((i, c) in candidates.withIndex()) {
            // keep scores positive and ordered; the actual value only matters relative to other gesture results
            val score = 1_000_000 + (c.score * 10_000).toInt() - i
            results.add(SuggestedWordInfo(c.word, "", score, SuggestedWordInfo.KIND_CORRECTION, GESTURE_DICTIONARY,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
        }
        return results
    }

    private fun getIndex(lexicon: GestureLexicon, layout: GestureDecoder.KeyLayout): GestureDecoder.WordIndex {
        val cached = indexCache
        val learningVersion = SmartLearning.version
        if (cached != null && cached.locale == lexicon.locale && cached.layoutSignature == layout.signature
            && (cached.learningVersion == learningVersion || learningVersion - cached.learningVersion < 60)) {
            return cached.index
        }
        val start = System.currentTimeMillis()
        val learned = SmartLearning.snapshot()
        val known = HashMap<String, Int>(lexicon.words.size * 2)
        lexicon.words.forEachIndexed { i, w -> known[w] = i }
        val extraWords = learned.keys.filter { it !in known && it.lowercase() !in known }
        val words = Array(lexicon.words.size + extraWords.size) { if (it < lexicon.words.size) lexicon.words[it] else extraWords[it - lexicon.words.size] }
        val weights = FloatArray(words.size) { i ->
            val base = if (i < lexicon.words.size) GestureDecoder.probabilityToWeight(lexicon.probabilities[i]) else 0.35f
            val personal = learned[words[i]] ?: 0
            base + if (personal > 0) min(0.45f, 0.12f * ln(1f + personal) + 0.1f) else 0f
        }
        val index = GestureDecoder.WordIndex.build(layout, words, weights)
        indexCache = IndexCache(lexicon.locale, layout.signature, learningVersion, index)
        Log.i(TAG, "gesture index for ${lexicon.locale} built in ${System.currentTimeMillis() - start} ms")
        return index
    }

    private fun buildLayout(keyboard: Keyboard): GestureDecoder.KeyLayout? {
        val keyW = keyboard.mMostCommonKeyWidth.toFloat().coerceAtLeast(1f)
        val keyH = keyboard.mMostCommonKeyHeight.toFloat().coerceAtLeast(1f)
        val letters = ArrayList<Char>()
        val cx = ArrayList<Float>()
        val cy = ArrayList<Float>()
        for (key in keyboard.sortedKeys) {
            val code = key.code
            if (code <= 0 || code > 0xFFFF || !Character.isLetter(code)) continue
            val c = Character.toLowerCase(code.toChar())
            if (c in letters) continue
            letters.add(c)
            cx.add((key.x + key.width / 2f) / keyW)
            cy.add((key.y + key.height / 2f) / keyH)
        }
        if (letters.size < 10) return null
        return GestureDecoder.KeyLayout(letters.toCharArray(), cx.toFloatArray(), cy.toFloatArray())
    }
}
