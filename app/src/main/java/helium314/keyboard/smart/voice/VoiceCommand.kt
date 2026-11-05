// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import java.util.Locale

/** Voice editing commands. Only a whole utterance is treated as a command, never a part of dictation. */
sealed class VoiceCommand {
    data object DeleteLastWord : VoiceCommand()
    data object DeleteLastSentence : VoiceCommand()
    data object DeleteAll : VoiceCommand()
    data object Undo : VoiceCommand()
    data object Send : VoiceCommand()
    data object Stop : VoiceCommand()
    data object Finish : VoiceCommand()
    data object NewLine : VoiceCommand()
    data class Replace(val target: String, val replacement: String) : VoiceCommand()

    companion object {
        private val o = setOf(RegexOption.IGNORE_CASE)
        private val deleteWord = Regex("^(?:удали(?:ть)?|сотри|стереть|убери)(?: последнее| это)? слово$|^(?:delete|remove|erase)(?: the)?(?: last)? word$", o)
        private val deleteSentence = Regex("^(?:удали(?:ть)?|сотри|стереть|убери)(?: последнее| это)? предложение$|^(?:delete|remove|erase)(?: the)?(?: last)? sentence$", o)
        private val deleteAll = Regex("^(?:удали(?:ть)?|сотри|стереть) (?:всё|все|весь текст)$|^очисти(?:ть)?(?: поле| всё| все| текст)?$|^(?:delete|clear|erase) (?:all|everything)(?: text)?$", o)
        private val undo = Regex("^(?:отмени(?:ть)?|отмена|верни(?: как было)?)$|^undo(?: that)?$", o)
        private val send = Regex("^(?:отправь|отправить|отправляй)(?: сообщение)?$|^send(?: it| message| the message)?$", o)
        private val stop = Regex("^(?:стоп|хватит|закончи(?:ть)?(?: диктовку)?|выключи микрофон)$|^stop(?: listening| dictation)?$", o)
        private val finish = Regex("^(?:готово|заверши(?:ть)? диктовку|done|finish dictation)$", o)
        private val newLine = Regex("^(?:новая строка|с новой строки|новый абзац)$|^new (?:line|paragraph)$", o)
        private val replace = Regex("^(?:замени(?:ть)?|исправь|поменяй) (.+?) на (.+)$|^(?:replace|change) (.+?) (?:with|to) (.+)$", o)

        fun normalize(text: String): String =
            text.trim().trimEnd('.', '!', '?', '…', ' ').replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

        fun parse(text: String): VoiceCommand? {
            val t = normalize(text)
            if (t.isEmpty() || t.length > 120) return null
            return when {
                deleteWord.matches(t) -> DeleteLastWord
                deleteSentence.matches(t) -> DeleteLastSentence
                deleteAll.matches(t) -> DeleteAll
                undo.matches(t) -> Undo
                send.matches(t) -> Send
                stop.matches(t) -> Stop
                finish.matches(t) -> Finish
                newLine.matches(t) -> NewLine
                else -> replace.matchEntire(t)?.let { m ->
                    val g = m.groupValues
                    val target = (g[1].ifEmpty { g[3] }).trim().trim('"', '«', '»')
                    val repl = (g[2].ifEmpty { g[4] }).trim().trim('"', '«', '»')
                    // keep the original casing of the replacement as recognized
                    val origRepl = Regex("(?:на|with|to) (.+)$", o).find(text.trim().trimEnd('.', '!', '?'))?.groupValues?.get(1)?.trim()
                    if (target.isEmpty() || repl.isEmpty()) null
                    else Replace(target, if (origRepl != null && origRepl.equals(repl, ignoreCase = true)) origRepl else repl)
                }
            }
        }
    }
}

/** Pure text helpers for voice editing, operating on the text before the cursor. */
object VoiceTextOps {
    /** Number of chars to delete to remove the last word including the space before it. */
    fun lastWordDeleteLength(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && !before[i - 1].isLetterOrDigit()) i--
        while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] == '-' || before[i - 1] == '\'')) i--
        while (i > 0 && before[i - 1] == ' ') i--
        return before.length - i
    }

    /** Number of chars to delete to remove the last sentence (the trailing one, finished or not). */
    fun lastSentenceDeleteLength(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && (before[i - 1].isWhitespace() || before[i - 1] in ".!?…")) {
            if (before[i - 1] == '\n') break
            i--
        }
        while (i > 0 && before[i - 1] !in ".!?…\n") i--
        return if (i == 0) before.length else before.length - i
    }

    private fun fold(s: CharSequence) = s.toString().lowercase(Locale.ROOT).replace('ё', 'е')

    /** Finds the last occurrence of [target] (case- and ё-insensitive, whole words). Returns start index or -1. */
    fun findLast(text: CharSequence, target: String): Int {
        val ft = fold(text)
        val fg = fold(target)
        var idx = ft.lastIndexOf(fg)
        while (idx >= 0) {
            val beforeOk = idx == 0 || !ft[idx - 1].isLetterOrDigit()
            val end = idx + fg.length
            val afterOk = end >= ft.length || !ft[end].isLetterOrDigit()
            if (beforeOk && afterOk) return idx
            idx = if (idx == 0) -1 else ft.lastIndexOf(fg, idx - 1)
        }
        return -1
    }

    /** Keeps capitalization of the replaced word. */
    fun matchCase(original: CharSequence, replacement: String): String {
        if (replacement.isEmpty() || original.isEmpty()) return replacement
        return if (original[0].isUpperCase()) replacement.replaceFirstChar { it.uppercaseChar() }
        else if (replacement.length > 1 && replacement[1].isLowerCase()) replacement.replaceFirstChar { it.lowercaseChar() }
        else replacement
    }
}
