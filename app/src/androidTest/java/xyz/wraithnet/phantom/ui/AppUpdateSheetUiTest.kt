package xyz.wraithnet.phantom.ui

import androidx.activity.compose.setContent
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
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
import xyz.wraithnet.phantom.data.PhantomDeltaAsset
import xyz.wraithnet.phantom.ui.home.AppUpdateSheet
import xyz.wraithnet.phantom.ui.theme.PhantomTheme

@RunWith(AndroidJUnit4::class)
class AppUpdateSheetUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun betaReleaseIsClearlyLabeledBeforeUserConfirmsInstall() {
        val release = PhantomRelease("0.3.25-beta.1", "", "", 1234,
            "- Тест новых функций", isBeta = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PhantomTheme {
                        AppUpdateSheet(release, false, 0, 0L, false, null,
                            onLater = {}, onUpdate = {},
                            onDownloadInBrowser = {}, onCancelDownload = {})
                    }
                }
            }
            compose.awaitVisible(hasText("Бета-версия 0.3.25-beta.1"))
            compose.awaitVisible(hasText("Обновить"))
        }
    }

    @Test fun deltaShowsSavingsWithoutExtraBrowserButtonInNormalFlow() {
        val release = PhantomRelease(
            version = "0.3.26",
            downloadUrl = "",
            sha256 = "a".repeat(64),
            sizeBytes = 184_000_000,
            notes = "- Оптимизация",
            delta = PhantomDeltaAsset(
                fromVersion = "0.3.25",
                downloadUrl = "",
                sha256 = "b".repeat(64),
                sizeBytes = 4_000_000
            )
        )
        var installs = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PhantomTheme {
                        AppUpdateSheet(release, false, 0, 0L, false, null,
                            onLater = {}, onUpdate = { installs++ },
                            onDownloadInBrowser = {}, onCancelDownload = {})
                    }
                }
            }
            compose.awaitVisible(hasText("Экономное обновление", substring = true))
            compose.onAllNodes(hasText("Скачать через браузер")).assertCountEquals(0)
            compose.awaitVisible(hasText("Обновить")).performClick()
            compose.runOnIdle { assertTrue(installs == 1) }
        }
    }

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

    @Test fun differentSigningKeyDisablesInstallAndOpensRealBackupPath() {
        val release = PhantomRelease("0.3.18", "", "", 1234,
            "- Улучшен Android TV\\n- Исправлен размер окна")
        var installRequested by mutableStateOf(false)
        var backupRequested by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PhantomTheme {
                        AppUpdateSheet(release, false, 0, 0L, false, null,
                            onLater = {}, onUpdate = { installRequested = true },
                            onDownloadInBrowser = {}, onCancelDownload = {},
                            signingMismatch = true,
                            onOpenBackup = { backupRequested = true })
                    }
                }
            }
            compose.awaitVisible(hasText("Другая подпись приложения"))
            compose.awaitVisible(hasText("Недоступно") and isNotEnabled())
            compose.awaitVisible(hasText("Резервная копия")).performClick()
            compose.runOnIdle {
                assertTrue(backupRequested)
                assertTrue(!installRequested)
            }
            compose.onAllNodes(hasText("Скачать через браузер")).assertCountEquals(0)
        }
    }

    @Test fun televisionUpdateDialogKeepsActionsVisibleWithLongNotes() {
        val release = PhantomRelease("0.3.18", "", "", 1234L,
            (1..22).joinToString("\\n") { "- Изменение номер " + it + ": подробности исправления" })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tvConfiguration = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                        Configuration.UI_MODE_TYPE_TELEVISION
                }
                activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides tvConfiguration) {
                        PhantomTheme {
                            AppUpdateSheet(release, false, 0, 0L, false, null,
                                onLater = {}, onUpdate = {},
                                onDownloadInBrowser = {}, onCancelDownload = {})
                        }
                    }
                }
            }
            compose.awaitVisible(hasText("Обновление Phantom"))
            compose.awaitVisible(hasText("Ещё")).performClick()
            val later = compose.awaitVisible(hasText("Позже")).getUnclippedBoundsInRoot()
            val update = compose.awaitVisible(hasText("Обновить")).getUnclippedBoundsInRoot()
            assertTrue(later.bottom > 0.dp && update.bottom > 0.dp)
            assertTrue(later.right <= update.left)
            compose.awaitVisible(hasText("Меньше")).performClick()
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
