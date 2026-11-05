// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import helium314.keyboard.smart.voice.SpeechCorrector
import helium314.keyboard.smart.voice.VoiceCommand
import helium314.keyboard.smart.voice.VoiceTextOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechCorrectorTest {
    private fun c(raw: String, before: String? = "") = SpeechCorrector.correct(raw, before)

    @Test fun spokenPunctuationRu() {
        assertEquals("Привет, как дела?", c("привет запятая как дела вопросительный знак"))
        assertEquals("Встретимся в 18:30. Не опаздывай!", c("встретимся в 18:30 точка не опаздывай восклицательный знак"))
        assertEquals("С моей точки зрения", c("с моей точки зрения"))
        assertEquals("Это точка зрения автора", c("это точка зрения автора"))
        assertEquals("Первая строка\nвторая", c("первая строка новая строка вторая").replace("\nВ", "\nв"))
    }

    @Test fun capitalizationAndSpacing() {
        assertEquals(" и ещё одно", c("И ещё одно", "Я написал"))
        assertEquals(" Москва большая", c("Москва большая", "Я люблю"))
        assertEquals("Новое предложение", c("новое предложение", "Готово. "))
        assertEquals(" Новое", c("новое", "Готово."))
        assertEquals("3,5 килограмма", c("3,5 килограмма", "\n").trim())
    }

    @Test fun fillersRemoved() {
        assertEquals("Я думаю, что да", c("э я думаю ээ что да"))
    }

    @Test fun smartCommas() {
        assertEquals("Я знаю, что ты прав", c("я знаю что ты прав"))
        assertEquals("Я дома, а ты где", c("я дома а ты где"))
        assertEquals("Позвони, когда придёшь", c("позвони когда придёшь"))
        assertEquals("Не потому что", c("не потому что"))
        assertEquals("Дом, который построил Джек", c("дом который построил Джек"))
        // recognizer already punctuated -> leave commas alone
        assertEquals("Я знаю, что ты прав и что он", c("Я знаю, что ты прав и что он"))
    }

    @Test fun questions() {
        assertEquals("Почему ты не пришёл?", c("почему ты не пришёл"))
        assertEquals("Как дела?", c("как дела"))
        assertEquals("Как только придёшь позвони", c("как только придёшь позвони"))
        assertEquals("Are you there?", c("are you there"))
        assertEquals("Hello world", c("hello world"))
    }

    @Test fun numbersAndNamesUntouched() {
        assertEquals("Перевёл Ивану Петрову 1500 рублей 12.03", c("перевёл Ивану Петрову 1500 рублей 12.03"))
    }

    @Test fun punctuationCanCompletePreviousSegment() {
        assertEquals(",", c("запятая", "Привет"))
        assertEquals(".", c("точка", "Готово"))
    }

    @Test fun urlsAndEmailAreNotRewritten() {
        assertEquals("Открой https://example.com/Period?a=Yes", c("открой https://example.com/Period?a=Yes"))
        assertEquals("Пиши user.Name@example.com", c("пиши user.Name@example.com"))
    }

    @Test fun commands() {
        assertEquals(VoiceCommand.DeleteLastSentence, VoiceCommand.parse("Удали последнее предложение"))
        assertEquals(VoiceCommand.DeleteLastWord, VoiceCommand.parse("удали слово."))
        assertEquals(VoiceCommand.Replace("встреча", "Созвон"), VoiceCommand.parse("замени встреча на Созвон"))
        assertEquals(VoiceCommand.Replace("monday", "Tuesday"), VoiceCommand.parse("Replace Monday with Tuesday"))
        assertEquals(VoiceCommand.Send, VoiceCommand.parse("Отправь"))
        assertEquals(VoiceCommand.Undo, VoiceCommand.parse("отмени"))
        assertNull(VoiceCommand.parse("удали последнее предложение из письма пожалуйста"))
        assertNull(VoiceCommand.parse("я сказал отправь ему"))
    }

    @Test fun textOps() {
        assertEquals(" мир".length, VoiceTextOps.lastWordDeleteLength("Привет мир"))
        assertEquals(" мир!".length, VoiceTextOps.lastWordDeleteLength("Привет мир!"))
        val t = "Первое. Второе предложение."
        assertEquals(t.length - "Первое.".length, VoiceTextOps.lastSentenceDeleteLength(t))
        assertEquals("Одно предложение".length, VoiceTextOps.lastSentenceDeleteLength("Одно предложение"))
        assertEquals(7, VoiceTextOps.findLast("Завтра встреча в 10", "Встреча"))
        assertEquals(-1, VoiceTextOps.findLast("Завтра встречами", "встреча"))
        assertEquals("Созвон", VoiceTextOps.matchCase("Встреча", "созвон"))
    }
}
