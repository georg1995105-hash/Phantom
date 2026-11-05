// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import java.util.Locale

/**
 * Local, deterministic post-processing of speech recognizer output. It never rewrites words:
 * numbers, names and word order stay as recognized. It only converts spoken punctuation,
 * removes hesitation sounds, fixes spacing and capitalization and (optionally) adds the most
 * reliable Russian commas and question marks.
 */
object SpeechCorrector {

    data class Options(
        val smartPunctuation: Boolean = true,
        val removeFillers: Boolean = true,
    )

    private const val L = "[\\p{L}\\p{N}]"
    private fun word(w: String) = Regex("(?<!$L)$w(?!$L)", RegexOption.IGNORE_CASE)

    // order matters: longer phrases first
    private val spokenPunctuation: List<Pair<Regex, String>> = listOf(
        "точка с запятой" to ";",
        "вопросительный знак" to "?",
        "знак вопроса" to "?",
        "восклицательный знак" to "!",
        "знак восклицания" to "!",
        "открыть скобку" to "(",
        "открывающая скобка" to "(",
        "закрыть скобку" to ")",
        "закрывающая скобка" to ")",
        "новый абзац" to "\n\n",
        "с новой строки" to "\n",
        "новая строка" to "\n",
        "многоточие" to "…",
        "двоеточие" to ":",
        "запятая" to ",",
        "точка(?! (?:зрения|отсчёта|отсчета|опоры|кипения|доступа|роста|соприкосновения|невозврата|ру|ком))" to ".",
        "question mark" to "?",
        "exclamation (?:mark|point)" to "!",
        "full stop" to ".",
        "semicolon" to ";",
        "new paragraph" to "\n\n",
        "new line" to "\n",
        "open (?:bracket|parenthesis)" to "(",
        "close (?:bracket|parenthesis)" to ")",
        "ellipsis" to "…",
        "comma" to ",",
        "period" to ".",
        "colon" to ":",
    ).map { (phrase, sign) -> word(phrase) to sign }

    private val dash = Regex("(?<!$L)(?:тире|dash)(?!$L)", RegexOption.IGNORE_CASE)
    private val fillers = Regex("(?<!$L)(?:э+|ээ+|эм+|мм+|uh+|um+|uhm+|erm+|hmm+)(?!$L)[,.]?", RegexOption.IGNORE_CASE)

    private val ruCommaWords = listOf(
        "потому что", "так как", "для того чтобы", "несмотря на то что", "в то время как", "после того как",
        "но", "а", "однако", "зато", "чтобы", "если", "когда", "хотя", "поэтому", "пока",
        "что", "где", "куда", "откуда", "почему", "зачем",
        "котор(?:ый|ая|ое|ые|ого|ой|ому|ую|ым|ыми|ых|ом)",
    )
    private val ruCommaRegex = Regex("(?<=$L) ((?:${ruCommaWords.joinToString("|")}))(?!$L)", RegexOption.IGNORE_CASE)
    private val noCommaAfter = setOf(
        "и", "или", "а", "но", "да", "не", "ни", "то", "так", "потому", "оттого", "чем", "раз", "даже", "только", "лишь",
        "именно", "особенно", "тогда", "вот", "того", "для", "что", "ли", "же", "всё", "все", "вместо", "кроме", "как",
        "либо", "тем", "из-за", "при", "о", "об", "в", "на", "с", "у", "к", "по", "за", "от", "до", "без", "под", "над",
        "про", "через", "это", "я", "ты", "он", "она", "оно", "мы", "вы", "они",
    )

    private val ruQuestionStrong = setOf("почему", "зачем", "сколько", "разве", "неужели", "кто", "куда", "откуда", "чей", "чья", "чьё", "чье", "чьи", "долго", "можно")
    private val ruQuestionWeak = setOf("что", "как", "где", "когда", "какой", "какая", "какое", "какие", "каким", "какого", "какую", "каких", "который", "которая", "которое", "ты", "вы")
    private val ruQuestionBlockers = setOf("только", "то", "так", "если", "бы", "будто", "надо", "нужно")
    private val enQuestionStart = setOf(
        "what", "why", "how", "where", "when", "who", "whom", "whose", "which", "is", "are", "am", "do", "does", "did",
        "can", "could", "will", "would", "should", "shall", "may", "might", "have", "has", "isn't", "aren't", "don't",
        "doesn't", "didn't", "can't", "won't", "wouldn't", "shouldn't", "couldn't",
    )
    private val leadIns = setOf("а", "и", "ну", "так", "слушай", "скажи", "and", "so", "but", "hey")

    /** Common words that are lowercase in the middle of a sentence; recognizers capitalize every result. */
    private val lowercaseWords = setOf(
        "я", "ты", "он", "она", "оно", "мы", "вы", "они", "и", "а", "но", "или", "что", "как", "где", "когда", "это", "этот",
        "эта", "эти", "то", "так", "там", "тут", "здесь", "да", "нет", "не", "ну", "вот", "уже", "ещё", "еще", "просто",
        "очень", "можно", "нужно", "надо", "потом", "сейчас", "тоже", "также", "только", "если", "чтобы", "потому",
        "в", "на", "с", "со", "к", "ко", "у", "о", "об", "по", "за", "из", "от", "до", "для", "без", "при", "про",
        "мне", "меня", "тебе", "тебя", "его", "её", "ее", "нам", "вам", "им", "их", "мой", "моя", "моё", "мои", "твой",
        "давай", "хорошо", "ладно", "спасибо", "пожалуйста", "привет", "пока", "сегодня", "завтра", "вчера", "все", "всё",
        "the", "a", "an", "and", "or", "but", "so", "to", "of", "in", "on", "at", "for", "with", "it", "is", "are", "was",
        "we", "you", "he", "she", "they", "this", "that", "there", "here", "yes", "no", "not", "please", "thanks", "ok", "okay",
    )

    private val sentenceEnd = Regex("[.!?…]\\s*$")

    fun isSentenceStart(before: CharSequence?): Boolean {
        if (before == null) return true
        val t = before.toString()
        if (t.isBlank()) return true
        if (t.endsWith("\n")) return true
        return sentenceEnd.containsMatchIn(t)
    }

    /** Whether a space is needed between [before] and [text]. */
    fun needsLeadingSpace(before: CharSequence?, text: String): Boolean {
        if (before.isNullOrEmpty() || text.isEmpty()) return false
        val last = before[before.length - 1]
        if (last.isWhitespace() || last == '(' || last == '«' || last == '"' || last == '/' || last == '@') return false
        val first = text[0]
        if (first in ",.!?:;…)»" || first == '\n') return false
        return true
    }

    /** Light-weight processing for partial (not yet final) results. */
    fun correctPartial(raw: String, before: CharSequence?, opts: Options = Options()): String =
        correct(raw, before, opts, isFinal = false)

    fun correct(raw: String, before: CharSequence?, opts: Options = Options(), isFinal: Boolean = true): String {
        var t = raw.replace(Regex("[ \\t\\u00A0]+"), " ").trim()
        if (t.isEmpty()) return ""
        // Do not split or capitalize inside URLs and email addresses.
        val protected = LinkedHashMap<String, String>()
        t = Regex("(?:https?://|www\\.)[^\\s]+|[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}|(?<![\\p{L}\\p{N}])(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/[^\\s]*)?|\\b\\d+(?:[.,:/-]\\d+)+\\b").replace(t) {
            val key = "\uE000${protected.size}\uE001"
            protected[key] = it.value
            key
        }
        if (opts.removeFillers) t = fillers.replace(t, "").replace(Regex(" {2,}"), " ").trim()
        val hadOwnCommas = t.contains(',')
        for ((regex, sign) in spokenPunctuation) t = regex.replace(t, Regex.escapeReplacement(" $sign "))
        t = dash.replace(t, " — ")
        t = normalizeSpacing(t)
        if (opts.smartPunctuation && !hadOwnCommas) t = addRussianCommas(t)
        t = capitalize(t, isSentenceStart(before))
        if (opts.smartPunctuation && isFinal) t = maybeAddQuestionMark(t)
        protected.forEach { (key, value) -> t = t.replace(key, value) }
        if (before.isNullOrBlank()) t = t.replace(Regex("^[,.;:]+ *"), "")
        if (t.isEmpty()) return ""
        return if (needsLeadingSpace(before, t)) " $t" else t
    }

    internal fun normalizeSpacing(input: String): String {
        var t = input
        t = t.replace(Regex(" *\\n *"), "\n")
        t = t.replace(Regex(" +([,.!?:;…)»])"), "$1")
        t = t.replace(Regex("([(«]) +"), "$1")
        // duplicated signs created by both the recognizer and spoken punctuation, e.g. "привет, запятая"
        t = t.replace(Regex("([,.!?:;])(?:\\s*\\1)+"), "$1")
        t = t.replace(Regex(",([.!?])"), "$1")
        t = t.replace(Regex("([,!?:;…])(?=[\\p{L}])"), "$1 ")
        t = t.replace(Regex("(?<=[\\p{L}])\\.(?=[\\p{Lu}])"), ". ")
        t = t.replace(Regex(" {2,}"), " ")
        t = t.trim(' ')
        // punctuation cannot start a dictated segment, except the line breaks
        // Leading punctuation may complete a previously dictated segment.
        return t
    }

    private fun addRussianCommas(input: String): String {
        if (!input.any { it in 'а'..'я' || it in 'А'..'Я' }) return input
        return ruCommaRegex.replace(input) { m ->
            val start = m.range.first
            val prefix = input.substring(0, start)
            val prevWord = prefix.takeLastWhile { it.isLetter() || it == '-' }.lowercase(Locale.ROOT)
            val firstConj = m.groupValues[1].lowercase(Locale.ROOT)
            val prevPrev = prefix.dropLast(prevWord.length).trimEnd().takeLastWhile { it.isLetter() }.lowercase(Locale.ROOT)
            val blocked = prevWord.isEmpty() || prevWord in noCommaAfter ||
                // "не только …, но и" is fine, but "я и не знаю что" has a leading negation
                (firstConj == "что" && prevPrev == "не") ||
                (firstConj == "а" && prefix.trimEnd().count { it == ' ' } < 1) ||
                // single-word sentences like "Хорошо что" are rare enough to keep the rule simple
                prefix.trimEnd().lastOrNull()?.let { it in ",.!?:;—-(\n" } == true
            if (blocked) m.value else "," + m.value
        }
    }

    internal fun capitalize(input: String, atSentenceStart: Boolean): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input)
        val firstLetter = sb.indexOfFirst { it.isLetter() }
        val firstContent = sb.indexOfFirst { !it.isWhitespace() && it != '"' && it != '«' && it != '(' }
        if (firstLetter >= 0 && firstLetter == firstContent) {
            if (atSentenceStart) {
                sb.setCharAt(firstLetter, sb[firstLetter].uppercaseChar())
            } else {
                val firstWord = sb.substring(firstLetter).takeWhile { it.isLetter() }
                if (firstWord.length > 1 && firstWord.drop(1).all { it.isLowerCase() } && firstWord.lowercase() in lowercaseWords)
                    sb.setCharAt(firstLetter, sb[firstLetter].lowercaseChar())
                else if (firstWord.length == 1 && firstWord.lowercase() in lowercaseWords && firstWord != "I")
                    sb.setCharAt(firstLetter, sb[firstLetter].lowercaseChar())
            }
        }
        var capNext = false
        for (i in sb.indices) {
            val c = sb[i]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') {
                // "3.5" or "т.е." must not trigger capitalization
                val nextIsSpaceOrBreak = c == '\n' || (i + 1 < sb.length && (sb[i + 1] == ' ' || sb[i + 1] == '\n'))
                capNext = nextIsSpaceOrBreak
            } else if (c.isLetter()) {
                if (capNext) sb.setCharAt(i, c.uppercaseChar())
                capNext = false
            } else if (!c.isWhitespace() && c != '"' && c != '«' && c != '(') {
                capNext = false
            }
        }
        return sb.toString()
    }

    private fun maybeAddQuestionMark(input: String): String {
        val trimmed = input.trimEnd()
        if (trimmed.isEmpty()) return input
        if (trimmed.last() in ".!?…:;,)\n") return input
        // only consider the last sentence
        val lastSentenceStart = trimmed.indexOfLast { it in ".!?…\n" } + 1
        val sentence = trimmed.substring(lastSentenceStart)
        val words = sentence.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}'-]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return input
        val w = if (words.size > 1 && words[0] in leadIns) words.drop(1) else words
        val first = w.first()
        val isQuestion = when {
            first in enQuestionStart -> words.size >= 2
            first in ruQuestionStrong -> true
            w.size >= 2 && w[1] == "ли" -> true
            words.size >= 3 && words.take(4).contains("ли") -> true
            first in ruQuestionWeak && first != "ты" && first != "вы" -> words.size <= 8 && words.none { it in ruQuestionBlockers } && !sentence.contains(',')
            else -> false
        }
        return if (isQuestion) "$trimmed?" else input
    }
}
