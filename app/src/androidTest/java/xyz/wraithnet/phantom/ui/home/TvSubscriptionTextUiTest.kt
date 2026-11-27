package xyz.wraithnet.phantom.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.data.Subscription

/** TV subscription details must remain readable and scroll to their actions. */
@RunWith(AndroidJUnit4::class)
class TvSubscriptionTextUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longSubscriptionTitleRendersWithoutOverflowAndButtonScrollsIntoView() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                Configuration.UI_MODE_TYPE_TELEVISION
            fontScale = 1.35f
        }
        val title = "Подписка с очень длинным названием провайдера и большим количеством " +
            "подключений, где должны полностью отображаться сведения о сроке и трафике"
        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(context.resources.displayMetrics.density, 1.35f)
            ) {
                MaterialTheme {
                    Column(Modifier.width(600.dp).height(460.dp).verticalScroll(rememberScrollState())) {
                        val sub = Subscription(id = "tv", title = title,
                            source = "https://sub2.wraithnet.xyz/abcdefgh12345678", servers = emptyList())
                        SubscriptionAccessCard(subscription = sub, openRenewal = {})
                    }
                }
            }
        }
        compose.onNode(hasText(title)).assertIsDisplayed()
        compose.onNode(hasText("Продлить подписку")).performScrollTo().assertIsDisplayed()
    }
}
