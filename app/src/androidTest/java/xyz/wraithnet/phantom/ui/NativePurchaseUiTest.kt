package xyz.wraithnet.phantom.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import xyz.wraithnet.phantom.data.PurchaseClient
import xyz.wraithnet.phantom.data.RenewalUnavailable
import xyz.wraithnet.phantom.ui.home.NativePurchaseScreen

@RunWith(AndroidJUnit4::class)
class NativePurchaseUiTest {
    @get:Rule val compose = createComposeRule()
    private class Store : PurchaseClient {
        var purchases = 0
        var resumes = 0
        var grantedSource: String? = null
        var recovered = false
        var expectRenewal = false
        var expectedPlan = 27
        var expectedAmount = "349.00"
        var renewalError: String? = null
        var draft: JSONObject? = null
        val catalog = JSONObject("""{"sales_enabled":true,"payment_available":true,"cards":[],"plans":[{"id":27,"name":"Проверочный тариф","devices":8,"traffic_gb":987,"durations":[{"days":45,"amount":"349.00"}]}]}""")
        override suspend fun sync() = catalog
        override suspend fun renewal(source: String): JSONObject {
            grantedSource = source
            renewalError?.let { throw RenewalUnavailable(it) }
            return JSONObject().put("access_id", "owned").put("plan_id", 27)
        }
        override suspend fun recover(code: String): JSONObject {
            assertEquals("a".repeat(64),code); recovered = true
            return JSONObject().put("access_id","recovered")
        }
        override suspend fun checkout(plan: JSONObject,duration: JSONObject,aid: String?,renew: Boolean): JSONObject {
            assertEquals(expectedPlan, plan.getInt("id"))
            assertEquals(expectedAmount, duration.getString("amount"))
            assertEquals(expectRenewal,renew)
            if (expectRenewal) assertEquals("owned",aid) else assertNull(aid)
            purchases++
            draft = JSONObject().put("mode", if (renew) "renew" else "new")
                .put("days", duration.getInt("days")).put("amount", duration.getString("amount"))
            return JSONObject().put("access_id","owned").put("status","PENDING")
        }
        override suspend fun resumeCheckout(): JSONObject? {
            if (draft == null) return null
            resumes++
            return JSONObject().put("access_id", "previous").put("status", "PENDING")
        }
        override fun pending() = draft
        override fun recoveryCode(access: String?) = ""
    }
    @Test fun actualServerOfferNeedsConsentAndDoesNotRequireCredentials() {
        val store = Store()
        compose.setContent { MaterialTheme { NativePurchaseScreen({},store) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Проверочный тариф").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Устройств: 8 · 987 ГБ / месяц").assertExists()
        compose.onNodeWithText("Оплатить · 349 ₽").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 349 ₽").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.purchases == 1 }
        compose.onNodeWithText("Продолжить оплату").assertExists()
        compose.onNodeWithText("Пароль").assertDoesNotExist()
        compose.onNodeWithText("Электронная почта").assertDoesNotExist()
        compose.onNodeWithText("Подписка добавлена. Можно подключаться к VPN.").assertDoesNotExist()
    }
    @Test fun tappingPlanCardChangesItsSelectionAndUsesItsOwnTerms() {
        val store = Store().apply {
            expectedPlan = 28
            expectedAmount = "650.00"
            catalog.getJSONArray("plans").put(JSONObject(
                """{"id":28,"name":"Семейный","devices":6,"traffic_gb":1500,"durations":[{"days":30,"amount":"250.00"},{"days":90,"amount":"650.00"}]}"""
            ))
        }
        var externalShopOpens = 0
        compose.setContent { MaterialTheme {
            NativePurchaseScreen({}, store, accessCard = {}, openShop = { externalShopOpens++ })
        } }
        compose.onNodeWithText("Тарифы").assertIsDisplayed()
        compose.onNodeWithText("Купить подписку").assertDoesNotExist()
        compose.onNodeWithText("Срок подписки").assertDoesNotExist()
        compose.onNodeWithText("В магазин").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, externalShopOpens) }
        compose.onNodeWithText("Семейный").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("250 ₽").performClick()
        compose.onNodeWithText("Семейный").assertIsSelected()
        compose.onNodeWithText("90 дней").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Оплатить · 650 ₽").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 650 ₽").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.purchases == 1 }
    }

    @Test fun importedSubscriptionRenewsItsOriginalOwner() {
        val eligible = Store().apply { expectRenewal = true }
        val admin = Store().apply { renewalError = "В магазине нет тарифа для продления этой подписки." }
        val store = mutableStateOf(admin)
        var reason: String? = null
        val source = "https://sub2.wraithnet.xyz/abcdefgh12345678"
        compose.setContent { MaterialTheme {
            key(store.value) {
                NativePurchaseScreen({}, store.value, renewSource = source, renewTitle = "Подписка клиента",
                    onRenewalUnavailable = { reason = it },
                )
            }
        } }
        compose.waitUntil(5000) { reason == admin.renewalError }
        compose.runOnIdle {
            assertEquals(0, admin.purchases)
            store.value = eligible
        }
        compose.onNodeWithText("Подписка клиента").assertExists()
        compose.onNodeWithText("Купить подписку").assertDoesNotExist()
        compose.waitUntil(5000) { eligible.grantedSource == source }
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 349 ₽").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { eligible.purchases == 1 }
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

    @Test fun pendingNewPurchaseStaysSeparateFromRenewalOfAnotherSubscription() {
        val store = Store().apply {
            draft = JSONObject().put("mode", "new").put("days", 90).put("amount", "400.00")
        }
        compose.setContent { MaterialTheme {
            NativePurchaseScreen({}, store,
                renewSource = "https://sub2.wraithnet.xyz/abcdefgh12345678",
                renewTitle = "Подписка для продления",
            )
        } }
        compose.onNodeWithText("Новая подписка · 90 дн. · 400.00 ₽").assertExists()
        compose.onNodeWithText("Оплатить · 349 ₽").assertDoesNotExist()
        compose.onNodeWithText("Продолжить оплату").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.resumes == 1 }
        compose.runOnIdle {
            assertNull(store.grantedSource)
            assertEquals(0, store.purchases)
        }
    }
}
