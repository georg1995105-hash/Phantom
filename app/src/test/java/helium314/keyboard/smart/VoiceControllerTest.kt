// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart

import android.Manifest
import android.os.Bundle
import android.os.Looper
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
    @Before fun setup() {
        ime = Robolectric.setupService(LatinIME::class.java)
        ShadowInputMethodService.reset()
        shadowOf(ime.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        ime.prefs().edit().putBoolean(SmartPrefs.VOICE_PREFER_OFFLINE, true)
            .putBoolean(SmartPrefs.VOICE_CONTINUOUS, false).apply()
        voice = ime.svVoice()
        voice.open()
        shadowOf(Looper.getMainLooper()).idle()
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
    @Test fun idleRecognizerTimesOut() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(VoiceController.State.PAUSED, voice.state)
    }
}
