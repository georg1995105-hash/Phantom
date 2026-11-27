package xyz.wraithnet.phantom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.ui.home.SubscriptionPage

@RunWith(AndroidJUnit4::class)
class SubscriptionStorefrontUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun subscriptionSectionHasOnePurchaseActionWithoutAnAccountForm() {
        var opens = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { SubscriptionPage { opens++ } } } }
        compose.onAllNodesWithText("Купить подписку").assertCountEquals(1)
        compose.onNodeWithText("Купить подписку").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        for (extra in listOf("Пароль", "Электронная почта", "Ваш доступ", "В магазин", "Личный кабинет"))
            compose.onNodeWithText(extra).assertDoesNotExist()
    }
}
