// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.smart.SmartPrefs

/** User-requested system language-pack check. No private text, audio or dictionary is sent. */
class VoiceModelActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var answered = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            showStatus("Локальный сервис не предоставляет загрузку моделей. Откройте настройки голосового ввода Android, выберите сервис и проверьте русский язык.")
            return
        }
        val tag = prefs().getString(SmartPrefs.VOICE_LANGUAGE, "auto").let { if (it == "en-US") it else "ru-RU" }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            handler.postDelayed({ showStatus("Сервис не ответил на проверку. Проверьте языковой пакет в настройках голосового ввода Android.") }, 8000)
            recognizer!!.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (answered || isFinishing) return
                    val language = tag.substringBefore('-')
                    fun contains(tags: List<String>) = tags.any { it.substringBefore('-').equals(language, true) }
                    when {
                        contains(support.installedOnDeviceLanguages) -> showStatus("Локальная модель $tag установлена. Точность зависит от системного сервиса; личные термины можно добавить в словарь диктовки.")
                        contains(support.pendingOnDeviceLanguages) -> showStatus("Модель $tag уже загружается системой. После завершения повторите проверку.")
                        contains(support.supportedOnDeviceLanguages) -> {
                            answered = true; handler.removeCallbacksAndMessages(null)
                            AlertDialog.Builder(this@VoiceModelActivity).setTitle("Языковая модель $tag")
                                .setMessage("Системный сервис может загрузить локальную модель через интернет. Размер и условия определяет этот сервис.")
                                .setPositiveButton("Загрузить") { _, _ ->
                                    answered = false
                                    val ok = runCatching { recognizer?.triggerModelDownload(intent) }.isSuccess
                                    showStatus(if (ok) "Запрос загрузки передан системе. Это ещё не подтверждение установки — повторите проверку позже." else "Сервис отклонил загрузку. Откройте его настройки.")
                                }.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                        }
                        else -> showStatus("Этот локальный сервис не сообщает поддержку $tag. Сравните системный способ распознавания в настройках диктовки.")
                    }
                }
                override fun onError(error: Int) { showStatus("Сервис не смог проверить модель (код $error). Проверьте язык в настройках голосового ввода Android.") }
            })
        } catch (_: Exception) { showStatus("Проверка модели недоступна у установленного сервиса.") }
    }
    private fun showStatus(message: String) {
        if (answered || isFinishing || isDestroyed) return
        answered = true; handler.removeCallbacksAndMessages(null)
        AlertDialog.Builder(this).setTitle("Голосовой ввод").setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNeutralButton("Настройки Android") { _, _ ->
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                finish()
            }.setOnCancelListener { finish() }.show()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        super.onDestroy()
    }
}
