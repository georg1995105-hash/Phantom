// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.Manifest
import android.os.Bundle
import android.os.Looper
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.smart.voice.VoiceController
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class,
    ShadowInputMethodService::class, ShadowKeyboardSwitcher::class])
class VoiceControllerTest {
    private lateinit var ime: LatinIME
    private lateinit var voice: VoiceController
    private var originalVoicePrefs = emptyMap<String, Any?>()
    @Before fun setup() {
        ime = Robolectric.setupService(LatinIME::class.java)
        originalVoicePrefs = ime.prefs().all.filterKeys { it.startsWith("sv_voice_") }
        ShadowInputMethodService.reset()
        shadowOf(ime.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        ime.prefs().edit().putBoolean(SmartPrefs.VOICE_PREFER_OFFLINE, true)
            .putBoolean(SmartPrefs.VOICE_CONTINUOUS, false)
            .putBoolean(SmartPrefs.VOICE_COMMANDS, false)
            .putBoolean(SmartPrefs.VOICE_SMART_PUNCTUATION, true)
            .putBoolean(SmartPrefs.VOICE_VOCABULARY_ENABLED, false).apply()
        voice = ime.svVoice()
        voice.open()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun cleanup() {
        voice.close()
        val editor = ime.prefs().edit()
        ime.prefs().all.keys.filter { it.startsWith("sv_voice_") }.forEach(editor::remove)
        originalVoicePrefs.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
        } }
        editor.apply()
    }
    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
    @Test fun partialIsReplacedByFinalAndDuplicateIsIgnored() {
        voice.onPartialResults(result("привет"))
        voice.onResults(result("привет мир"))
        assertEquals("Привет мир", ShadowInputMethodService.text)
        voice.onResults(result("привет мир"))
        assertEquals("Привет мир", ShadowInputMethodService.text)
        assertEquals(VoiceController.State.PAUSED, voice.state)
    }
    @Test fun emptyFinalKeepsPartial() {
        voice.onPartialResults(result("последнее слово"))
        voice.onResults(Bundle())
        assertEquals("Последнее слово", ShadowInputMethodService.text)
    }
    @Test fun doneWaitsForFinalWords() {
        voice.onPartialResults(result("начало"))
        voice.finishByUser()
        assertEquals(VoiceController.State.PROCESSING, voice.state)
        voice.onResults(result("начало и конец"))
        assertEquals("Начало и конец", ShadowInputMethodService.text)
        assertEquals(VoiceController.State.OFF, voice.state)
    }
    @Test fun doneWithoutFinalDoesNotHang() {
        voice.onPartialResults(result("сохранить"))
        voice.finishByUser()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("Сохранить", ShadowInputMethodService.text)
        assertEquals(VoiceController.State.OFF, voice.state)
    }
    @Test fun oldSessionCannotInsertIntoNewSession() {
        val previous = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        voice.close()
        voice.open()
        shadowOf(Looper.getMainLooper()).idle()
        previous.triggerOnResults(result("устаревший результат"))
        assertEquals("", ShadowInputMethodService.text)
        voice.onResults(result("новый результат"))
        assertEquals("Новый результат", ShadowInputMethodService.text)
    }
    @Test fun silenceDoesNotRestartForever() {
        ime.prefs().edit().putBoolean(SmartPrefs.VOICE_CONTINUOUS, true).apply()
        voice.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        voice.onError(SpeechRecognizer.ERROR_NO_MATCH)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(VoiceController.State.PAUSED, voice.state)
    }
    @Test fun processingHasFiniteDeadline() {
        voice.onPartialResults(result("конец фразы"))
        voice.onEndOfSpeech()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals("Конец фразы", ShadowInputMethodService.text)
        assertEquals(VoiceController.State.PAUSED, voice.state)
    }
    @Test fun lateReadyAndEndCannotExtendDoneDeadline() {
        voice.onPartialResults(result("последние слова"))
        voice.finishByUser()
        voice.onReadyForSpeech(Bundle())
        voice.onBeginningOfSpeech()
        voice.onEndOfSpeech()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("Последние слова", ShadowInputMethodService.text)
        assertEquals(VoiceController.State.OFF, voice.state)
    }
    @Test fun idleRecognizerTimesOut() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(VoiceController.State.PAUSED, voice.state)
    }
    @Test fun pauseDoesNotRestartAfterProviderBusyError() {
        voice.onPartialResults(result("не теряй конец"))
        voice.onMicTapped()
        voice.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(VoiceController.State.PAUSED, voice.state)
        assertEquals("Не теряй конец", ShadowInputMethodService.text)
    }
    @Test fun livePartialPreventsStartupTimeoutWhenReadyWasOmitted() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(14))
        voice.onPartialResults(result("длинная фраза продолжается"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(voice.isActive)
        assertNotEquals(VoiceController.State.PAUSED, voice.state)
    }
    @Test fun spokenDoneClosesWithoutInsertingTheCommand() {
        ime.prefs().edit().putBoolean(SmartPrefs.VOICE_COMMANDS, true).apply()
        voice.onPartialResults(result("готово"))
        voice.onResults(result("готово"))
        assertEquals("", ShadowInputMethodService.text)
        assertEquals(VoiceController.State.OFF, voice.state)
        voice.onPartialResults(result("поздний текст"))
        voice.onResults(result("поздний текст"))
        assertEquals("", ShadowInputMethodService.text)
    }
    private fun recognitionIntent() = VoiceController::class.java.getDeclaredMethod("recognizerIntent")
        .apply { isAccessible = true }.invoke(voice) as Intent
    @Test fun punctuationSettingControlsEngineFormattingToo() {
        ime.prefs().edit().putBoolean(SmartPrefs.VOICE_SMART_PUNCTUATION, false).apply()
        assertFalse(recognitionIntent().hasExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING))
    }
    @Test fun personalVocabularyIsNotSentToRemoteRecognizer() {
        ime.prefs().edit().putBoolean(SmartPrefs.VOICE_VOCABULARY_ENABLED, true)
            .putString(SmartPrefs.VOICE_VOCABULARY, "СекретноеИмя").apply()
        VoiceController::class.java.getDeclaredField("recognizerIsOnDevice")
            .apply { isAccessible = true }.setBoolean(voice, false)
        assertFalse(recognitionIntent().hasExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))
    }
    @Test fun preservesFormattedResultEvenWhenRawTextHasHigherConfidence() {
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Привет, мир.", "привет мир"))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(.2f, .9f))
        }
        voice.onResults(bundle)
        assertEquals("Привет, мир.", ShadowInputMethodService.text)
    }
}
