// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.gesture

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shape-matching gesture decoder in the spirit of SHARK2: the swipe path and the ideal path through
 * the key centers of each candidate word are compared after resampling, combined with word
 * frequency, personal history and the next-word context.
 *
 * All geometry is expressed in "key units" (x divided by key width, y by key height), so the
 * thresholds do not depend on the keyboard size.
 */
class GestureDecoder(private val layout: KeyLayout, private val lexicon: WordIndex) {

    /** Letter key centers of the current keyboard. */
    class KeyLayout(val letters: CharArray, val cx: FloatArray, val cy: FloatArray) {
        private val indexOf = HashMap<Char, Int>().apply { letters.forEachIndexed { i, c -> put(c, i) } }
        val signature: String = String(letters)
        fun keyIndex(c: Char): Int = indexOf[c] ?: -1
    }

    /** Words of a lexicon pre-mapped to key index sequences for one key layout. */
    class WordIndex(
        val words: Array<String>,
        val weights: FloatArray, // language model weight in 0..1
        val keySequences: Array<ByteArray?>,
        val byFirstKey: Array<IntArray>,
    ) {
        companion object {
            fun build(layout: KeyLayout, words: Array<String>, weights: FloatArray): WordIndex {
                val sequences = arrayOfNulls<ByteArray>(words.size)
                val buckets = Array(layout.letters.size) { ArrayList<Int>() }
                val tmp = ByteArray(64)
                for (i in words.indices) {
                    val seq = toKeySequence(layout, words[i], tmp) ?: continue
                    if (seq.size < 2) continue
                    sequences[i] = seq
                    buckets[seq[0].toInt()].add(i)
                }
                return WordIndex(words, weights, sequences, Array(buckets.size) { buckets[it].toIntArray() })
            }

            fun toKeySequence(layout: KeyLayout, word: String, tmp: ByteArray = ByteArray(64)): ByteArray? {
                var n = 0
                var last = -1
                for (ch in word) {
                    val c = when (val l = ch.lowercaseChar()) {
                        'ё' -> 'е'
                        else -> l
                    }
                    if (c == '\'' || c == '-' || c == '’' || c == 'ъ' && layout.keyIndex('ъ') < 0) continue
                    val k = layout.keyIndex(c)
                    if (k < 0) return null
                    if (k == last) continue
                    if (n >= tmp.size) return null
                    tmp[n++] = k.toByte()
                    last = k
                }
                return if (n == 0) null else tmp.copyOf(n)
            }
        }
    }

    class Candidate(val word: String, val score: Float, val geometry: Float)

    /**
     * @param xs, ys gesture points already in key units
     * @param contextBoost extra language weight for words, e.g. next-word predictions or learned words
     */
    fun decode(xs: FloatArray, ys: FloatArray, count: Int, contextBoost: Map<String, Float>, maxResults: Int): List<Candidate> {
        if (count < 2) return emptyList()
        val gx = FloatArray(SAMPLES)
        val gy = FloatArray(SAMPLES)
        val gestureLength = resample(xs, ys, count, gx, gy)
        if (gestureLength < MIN_GESTURE_LENGTH) return emptyList()
        val sx = xs[0]; val sy = ys[0]
        val ex = xs[count - 1]; val ey = ys[count - 1]

        val keyCount = layout.letters.size
        val startOk = BooleanArray(keyCount) { dist(layout.cx[it], layout.cy[it], sx, sy) <= END_RADIUS }
        val endOk = BooleanArray(keyCount) { dist(layout.cx[it], layout.cy[it], ex, ey) <= END_RADIUS }

        val best = TopList(CANDIDATE_POOL)
        val tx = FloatArray(SAMPLES)
        val ty = FloatArray(SAMPLES)
        for (k in 0 until keyCount) {
            if (!startOk[k]) continue
            for (wi in lexicon.byFirstKey[k]) {
                val seq = lexicon.keySequences[wi] ?: continue
                if (!endOk[seq[seq.size - 1].toInt()]) continue
                val geometry = scoreGeometry(seq, gx, gy, gestureLength, tx, ty) ?: continue
                val lm = lexicon.weights[wi] + (contextBoost[lexicon.words[wi]] ?: 0f)
                best.offer(wi, -geometry * GEOMETRY_WEIGHT + lm * LM_WEIGHT, geometry)
            }
        }
        val result = ArrayList<Candidate>(min(maxResults, best.size))
        for (i in best.sortedIndices().take(maxResults)) {
            result.add(Candidate(lexicon.words[best.ids[i]], best.scores[i], best.geometries[i]))
        }
        return result
    }

    /** Returns the geometric distance between gesture and word path, or null when the word is implausible. */
    private fun scoreGeometry(seq: ByteArray, gx: FloatArray, gy: FloatArray, gestureLength: Float,
                              tx: FloatArray, ty: FloatArray): Float? {
        var templateLength = 0f
        for (i in 1 until seq.size) {
            val a = seq[i - 1].toInt(); val b = seq[i].toInt()
            templateLength += dist(layout.cx[a], layout.cy[a], layout.cx[b], layout.cy[b])
        }
        if (templateLength < 0.5f) return null
        val ratio = gestureLength / templateLength
        if (ratio < 0.55f || ratio > 1.9f) return null

        // letters must be passed in order; the gesture may miss a letter by at most one key
        var alignment = 0f
        var misses = 0
        var j = 0
        for (i in seq.indices) {
            val k = seq[i].toInt()
            val kx = layout.cx[k]; val ky = layout.cy[k]
            var bestD = Float.MAX_VALUE
            var bestJ = j
            val last = if (i == seq.size - 1) SAMPLES - 1 else SAMPLES - (seq.size - 1 - i)
            var m = if (i == 0) 0 else j
            while (m <= last) {
                val d = dist(gx[m], gy[m], kx, ky)
                if (d < bestD) { bestD = d; bestJ = m }
                // stop when moving away again after having been close
                if (bestD < 0.45f && d > bestD + 0.9f) break
                m++
            }
            if (i == 0) { bestD = dist(gx[0], gy[0], kx, ky); bestJ = 0 }
            if (i == seq.size - 1) bestD = min(bestD, dist(gx[SAMPLES - 1], gy[SAMPLES - 1], kx, ky))
            if (bestD > PASS_RADIUS) {
                misses++
                if (misses > (if (seq.size >= 6) 1 else 0)) return null
            }
            alignment += bestD
            j = bestJ
        }
        resampleTemplate(seq, templateLength, tx, ty)
        var location = 0f
        for (i in 0 until SAMPLES) location += dist(gx[i], gy[i], tx[i], ty[i])
        location /= SAMPLES
        return location + ALIGNMENT_WEIGHT * alignment / seq.size + misses * MISS_PENALTY + abs(ln(ratio)) * 0.25f
    }

    private fun resampleTemplate(seq: ByteArray, length: Float, outX: FloatArray, outY: FloatArray) {
        val step = length / (SAMPLES - 1)
        var seg = 0
        var segStartDist = 0f
        var ax = layout.cx[seq[0].toInt()]; var ay = layout.cy[seq[0].toInt()]
        var bx = layout.cx[seq[1].toInt()]; var by = layout.cy[seq[1].toInt()]
        var segLen = dist(ax, ay, bx, by)
        for (i in 0 until SAMPLES) {
            val target = i * step
            while (target > segStartDist + segLen && seg < seq.size - 2) {
                segStartDist += segLen
                seg++
                ax = bx; ay = by
                bx = layout.cx[seq[seg + 1].toInt()]; by = layout.cy[seq[seg + 1].toInt()]
                segLen = dist(ax, ay, bx, by)
            }
            val t = if (segLen <= 0f) 0f else ((target - segStartDist) / segLen).coerceIn(0f, 1f)
            outX[i] = ax + (bx - ax) * t
            outY[i] = ay + (by - ay) * t
        }
    }

    private class TopList(val capacity: Int) {
        val ids = IntArray(capacity)
        val scores = FloatArray(capacity)
        val geometries = FloatArray(capacity)
        var size = 0
        private var minIndex = 0

        fun offer(id: Int, score: Float, geometry: Float) {
            if (size < capacity) {
                ids[size] = id; scores[size] = score; geometries[size] = geometry
                size++
                if (size == capacity) recomputeMin()
                return
            }
            if (score <= scores[minIndex]) return
            ids[minIndex] = id; scores[minIndex] = score; geometries[minIndex] = geometry
            recomputeMin()
        }

        private fun recomputeMin() {
            var m = 0
            for (i in 1 until size) if (scores[i] < scores[m]) m = i
            minIndex = m
        }

        fun sortedIndices(): List<Int> = (0 until size).sortedByDescending { scores[it] }
    }

    companion object {
        const val SAMPLES = 48
        private const val END_RADIUS = 1.25f
        private const val PASS_RADIUS = 0.95f
        private const val MIN_GESTURE_LENGTH = 0.9f
        private const val CANDIDATE_POOL = 40
        private const val GEOMETRY_WEIGHT = 3.2f
        private const val LM_WEIGHT = 1.0f
        private const val ALIGNMENT_WEIGHT = 0.6f
        private const val MISS_PENALTY = 0.45f

        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }

        /** Resamples a polyline to [SAMPLES] equidistant points, returns its length. */
        fun resample(xs: FloatArray, ys: FloatArray, count: Int, outX: FloatArray, outY: FloatArray): Float {
            var length = 0f
            for (i in 1 until count) length += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            if (length <= 0f) {
                outX.fill(xs[0]); outY.fill(ys[0])
                return 0f
            }
            val step = length / (SAMPLES - 1)
            outX[0] = xs[0]; outY[0] = ys[0]
            var n = 1
            var accumulated = 0f
            var px = xs[0]; var py = ys[0]
            var i = 1
            while (i < count && n < SAMPLES) {
                val qx = xs[i]; val qy = ys[i]
                val d = dist(px, py, qx, qy)
                if (accumulated + d >= step && d > 0f) {
                    val t = (step - accumulated) / d
                    val nx = px + t * (qx - px)
                    val ny = py + t * (qy - py)
                    outX[n] = nx; outY[n] = ny
                    n++
                    px = nx; py = ny
                    accumulated = 0f
                } else {
                    accumulated += d
                    px = qx; py = qy
                    i++
                }
            }
            while (n < SAMPLES) { outX[n] = xs[count - 1]; outY[n] = ys[count - 1]; n++ }
            return length
        }

        fun probabilityToWeight(probability: Int): Float = (max(0, probability) / 255f)
    }
}
