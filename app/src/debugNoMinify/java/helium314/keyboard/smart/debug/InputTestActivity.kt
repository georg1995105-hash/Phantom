// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.debug

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*

/** Real Android fields for reproducible IME checks. Restricted to adb's DUMP permission. */
class InputTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        column.addView(TextView(this).apply { text = "Проверка SmartVoice · настоящее поле Android"; textSize = 16f })
        val received = TextView(this).apply { textSize = 12f; visibility = android.view.View.GONE }
        val thumbnail = ImageView(this).apply { visibility = android.view.View.GONE; adjustViewBounds = true; maxHeight = 160 }
        var receivedCount = 0
        fun field(id: Int, label: String, type: Int): EditText = object : EditText(this) {
            override fun onCreateInputConnection(info: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
                val connection = super.onCreateInputConnection(info) ?: return null
                if (id != 0x10001) return connection
                androidx.core.view.inputmethod.EditorInfoCompat.setContentMimeTypes(info, arrayOf("image/*"))
                @Suppress("DEPRECATION")
                return androidx.core.view.inputmethod.InputConnectionCompat.createWrapper(connection, info) { item, flags, _ ->
                    var permission = false
                    try {
                        if (flags and androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                            item.requestPermission(); permission = true
                        }
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(item.contentUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                        if (options.outWidth <= 0) return@createWrapper false
                        val sample = (options.outWidth / 160).coerceAtLeast(1)
                        val bitmap = contentResolver.openInputStream(item.contentUri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                        } ?: return@createWrapper false
                        receivedCount++
                        received.text = "Принято изображений: $receivedCount · ${item.description.getMimeType(0)}"
                        received.visibility = android.view.View.VISIBLE
                        thumbnail.setImageBitmap(bitmap); thumbnail.visibility = android.view.View.VISIBLE
                        true
                    } catch (_: Exception) { false }
                    finally { if (permission) item.releasePermission() }
                }
            }
        }.apply {
            this.id = id; hint = label; inputType = type
            textSize = 18f; minLines = 1; maxLines = 3
            column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val main = field(0x10001, "Введите текст", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        column.addView(received)
        column.addView(thumbnail, LinearLayout.LayoutParams(-1, 160))
        field(0x10002, "Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field(0x10003, "Пароль", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field(0x10004, "Число", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(column) })
        main.requestFocus()
        main.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(main, 0) }, 400)
    }
}
