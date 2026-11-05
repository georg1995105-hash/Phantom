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
        fun field(id: Int, label: String, type: Int): EditText = EditText(this).apply {
            this.id = id; hint = label; inputType = type
            textSize = 18f; minLines = 1; maxLines = 3
            column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val main = field(0x10001, "Введите текст", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        field(0x10002, "Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field(0x10003, "Пароль", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field(0x10004, "Число", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(column) })
        main.requestFocus()
        main.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(main, 0) }, 400)
    }
}
