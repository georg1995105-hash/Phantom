package xyz.wraithnet.phantom.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode
import xyz.wraithnet.phantom.ui.theme.phantomPageTop
import xyz.wraithnet.phantom.ui.theme.themeScheme

@RunWith(AndroidJUnit4::class)
class StatusBarThemeUiTest {
    @Test fun statusBarMatchesActualPageTopInEveryManualTheme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefs = instrumentation.targetContext.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        val prior = prefs.getString("theme", null)
        try {
            for (mode in listOf(PhantomThemeMode.LIGHT, PhantomThemeMode.GRAPHITE,
                PhantomThemeMode.NIGHT, PhantomThemeMode.FOREST)) {
                check(prefs.edit().putString("theme", mode.name).commit())
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val expected = themeScheme(mode, false).phantomPageTop().toArgb()
                    var matched = false
                    var observed = ""
                    for (attempt in 0 until 30) {
                        instrumentation.waitForIdleSync()
                        var inset = 0
                        var darkIcons = false
                        scenario.onActivity { activity ->
                            inset = ViewCompat.getRootWindowInsets(activity.window.decorView)
                                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                            darkIcons = WindowInsetsControllerCompat(activity.window,
                                activity.window.decorView).isAppearanceLightStatusBars
                        }
                        val image = instrumentation.uiAutomation.takeScreenshot()
                        if (image != null) {
                            try {
                                if (inset > 4 && inset + 2 < image.height) {
                                    // The middle of the strip avoids clock and right-hand status icons.
                                    val xs = (40..60 step 5).map { image.width * it / 100 }
                                    val above = xs.map { image.getPixel(it, inset - 2) }
                                    val below = xs.map { image.getPixel(it, inset + 2) }
                                    observed = "inset=$inset above=$above below=$below darkIcons=$darkIcons expected=$expected"
                                    matched = above.count { near(it, expected) } >= 3 &&
                                        below.count { near(it, expected) } >= 3 &&
                                        darkIcons == (mode == PhantomThemeMode.LIGHT)
                                }
                            } finally { image.recycle() }
                        }
                        if (matched) break
                        Thread.sleep(200)
                    }
                    assertTrue("$mode actual status/page boundary: $observed", matched)
                }
            }
        } finally {
            prefs.edit().apply {
                if (prior == null) remove("theme") else putString("theme", prior)
            }.commit()
        }
    }

    private fun near(actual: Int, expected: Int): Boolean =
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 6 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 6 &&
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 6
}
