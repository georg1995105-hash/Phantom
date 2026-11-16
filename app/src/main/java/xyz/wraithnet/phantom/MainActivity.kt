package xyz.wraithnet.phantom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.wraithnet.phantom.ui.home.HomeScreen
import xyz.wraithnet.phantom.ui.theme.PhantomTheme
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("phantom_preferences", MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(
                    runCatching {
                        PhantomThemeMode.valueOf(
                            preferences.getString("theme", PhantomThemeMode.GRAPHITE.name)
                                ?: PhantomThemeMode.GRAPHITE.name,
                        )
                    }.getOrDefault(PhantomThemeMode.GRAPHITE),
                )
            }
            PhantomTheme(mode = theme) {
                HomeScreen(onThemeChange = { selected ->
                    theme = selected
                    preferences.edit().putString("theme", selected.name).apply()
                })
            }
        }
    }
}
