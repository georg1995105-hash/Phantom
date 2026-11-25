package xyz.wraithnet.phantom.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity

@RunWith(AndroidJUnit4::class)
class SettingsDashboardUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realSettingsTilesOpenTheirActualScreensAndReturn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val priorTheme = prefs.getString("theme", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun back() {
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    compose.waitForIdle()
                }
                fun open(label: String) {
                    val target = hasText(label) and hasClickAction()
                    compose.onNode(hasScrollAction()).performScrollToNode(target)
                    compose.awaitVisible(target).performClick()
                }
                fun see(label: String) {
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(label, useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                }
                compose.awaitVisible(hasContentDescription("Вкладка Настройки")).performClick()
                open("Подключение")
                open("DNS")
                see("Сохранить")
                back()
                open("Пинг серверов")
                see("Автоматический пинг TUIC")
                back()
                open("Подключение")
                open("Тема оформления")
                see("Выберите внешний вид Phantom")
                open("Светлая")
                open("Тёмно-зелёная")
                back()
                open("Туннелирование")
                see("Приложения и VPN")
                back()
                open("Фоновая работа")
                see("Работа при выключенном экране")
                back()
                open("Резервная копия")
                see("Сохранить резервную копию")
                see("Восстановить из файла")
                back()
                open("О приложении")
                open("Компоненты и лицензии")
                see("ВОЗМОЖНОСТИ КЛИЕНТА")
                back()
                open("Журнал VPN")
                see("Журнал VPN")
                back()
                back()
                open("Автоподключение")
                open("Автоподключение")
                compose.awaitVisible(hasContentDescription("Вкладка Главная")).performClick()
                compose.awaitVisible(hasContentDescription("Вкладка Настройки")).performClick()
                see("Подключение")
            }
        } finally {
            prefs.edit().apply {
                if (priorTheme == null) remove("theme") else putString("theme", priorTheme)
            }.commit()
        }
    }
}
