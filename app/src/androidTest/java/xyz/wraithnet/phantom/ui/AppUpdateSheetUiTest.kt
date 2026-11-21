package xyz.wraithnet.phantom.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.data.PhantomRelease
import xyz.wraithnet.phantom.ui.home.AppUpdateSheet
import xyz.wraithnet.phantom.ui.theme.PhantomTheme

@RunWith(AndroidJUnit4::class)
class AppUpdateSheetUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun failureShowsBrowserFallbackWithoutBlockingWorkingUpdateButtons() {
        val release = PhantomRelease("9.0.0", "", "", 1234,
            "- Исправление загрузки через Wi-Fi")
        var browserInvoked by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PhantomTheme {
                        AppUpdateSheet(release, false, 0, 0L, false,
                            "GitHub недоступен по текущей сети",
                            onLater = { }, onUpdate = { },
                            onDownloadInBrowser = { browserInvoked = true },
                            onCancelDownload = { })
                    }
                }
            }
            compose.awaitVisible(hasText("Скачать через браузер")).performClick()
            compose.runOnIdle { assertTrue(browserInvoked) }
        }
    }

    @Test fun primaryAndLaterActionsStayTogetherBelowExpandableReleaseNotes() {
        val release = PhantomRelease("9.0.0", "", "", 0L,
            "- Первый пункт\n- Второй пункт\n- Третий пункт\n- Дополнительный пункт")
        var shown by mutableStateOf(true)
        var downloading by mutableStateOf(false)
        var stopped by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PhantomTheme {
                        if (shown) AppUpdateSheet(release, downloading, 40, 128L * 1024L, false, null,
                            onLater = { shown = false }, onUpdate = { downloading = true },
                            onDownloadInBrowser = { },
                            onCancelDownload = { stopped = true; downloading = false })
                    }
                }
            }
            compose.awaitVisible(hasText("Версия 9.0.0"))
            val later = hasText("Позже")
            val update = hasText("Обновить")
            val laterBounds = compose.awaitVisible(later).getUnclippedBoundsInRoot()
            val updateBounds = compose.awaitVisible(update).getUnclippedBoundsInRoot()
            assertTrue(laterBounds.right <= updateBounds.left)
            assertTrue(laterBounds.top < updateBounds.bottom && updateBounds.top < laterBounds.bottom)
            compose.awaitVisible(hasText("Ещё")).performClick()
            compose.awaitVisible(hasText("Дополнительный пункт", substring = true))
            compose.awaitVisible(hasText("Меньше")).performClick()
            compose.onNode(hasText("Дополнительный пункт", substring = true)).assertDoesNotExist()
            compose.awaitVisible(update).performClick()
            compose.awaitVisible(hasText("Загрузка 40% · 128 КБ/с"))
            compose.awaitVisible(hasText("Остановить") and isEnabled()).performClick()
            compose.runOnIdle { assertTrue(stopped) }
            compose.awaitVisible(later and isEnabled()).performClick()
            compose.onNode(update).assertDoesNotExist()
        }
    }
}
