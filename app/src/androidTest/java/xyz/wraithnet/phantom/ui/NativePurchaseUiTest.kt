package xyz.wraithnet.phantom.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import xyz.wraithnet.phantom.data.PurchaseClient
import xyz.wraithnet.phantom.ui.home.NativePurchaseScreen

@RunWith(AndroidJUnit4::class)
class NativePurchaseUiTest {
    @get:Rule val compose = createComposeRule()
    private class Store : PurchaseClient {
        var purchases = 0
        var recovered = false
        var draft: JSONObject? = null
        val catalog = JSONObject("""{"sales_enabled":true,"payment_available":true,"cards":[],"plans":[{"id":27,"name":"Проверочный тариф","devices":8,"traffic_gb":987,"durations":[{"days":45,"amount":"349.00"}]}]}""")
        override suspend fun sync() = catalog
        override suspend fun recover(code: String): JSONObject {
            assertEquals("a".repeat(64),code); recovered = true
            return JSONObject().put("access_id","recovered")
        }
        override suspend fun checkout(plan: JSONObject,duration: JSONObject,aid: String?,renew: Boolean): JSONObject {
            assertEquals(27,plan.getInt("id")); assertEquals("349.00",duration.getString("amount"))
            assertNull(aid); assertFalse(renew); purchases++; draft = JSONObject()
            return JSONObject().put("access_id","owned").put("status","PENDING")
        }
        override fun pending() = draft
        override fun recoveryCode(access: String?) = ""
    }
    @Test fun actualServerOfferNeedsConsentAndDoesNotRequireCredentials() {
        val store = Store()
        compose.setContent { MaterialTheme { NativePurchaseScreen({},store) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Проверочный тариф").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Устройств: 8 · 987 ГБ / месяц").assertExists()
        compose.onNodeWithText("Оплатить · 349.00 ₽").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 349.00 ₽").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.purchases == 1 }
        compose.onNodeWithText("Продолжить оплату").assertExists()
        compose.onNodeWithText("Пароль").assertDoesNotExist()
        compose.onNodeWithText("Электронная почта").assertDoesNotExist()
        compose.onNodeWithText("Подписка добавлена. Можно подключаться к VPN.").assertDoesNotExist()
    }
    @Test fun guestRecoveryIsAnIndependentAction() {
        val store = Store()
        compose.setContent { MaterialTheme { NativePurchaseScreen({},store) } }
        compose.onNodeWithText("Восстановить покупку").performScrollTo().performClick()
        compose.onNodeWithText("Код восстановления").performTextInput("a".repeat(64))
        compose.onNodeWithText("Восстановить").performClick()
        compose.waitUntil(5000) { store.recovered }
        compose.runOnIdle { assertEquals(0,store.purchases) }
    }
}
