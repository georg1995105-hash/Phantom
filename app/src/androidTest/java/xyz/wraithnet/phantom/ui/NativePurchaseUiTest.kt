package xyz.wraithnet.phantom.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import xyz.wraithnet.phantom.data.PurchaseClient
import xyz.wraithnet.phantom.data.RenewalUnavailable
import xyz.wraithnet.phantom.ui.home.NativePurchaseScreen
import xyz.wraithnet.phantom.ui.home.SubscriptionOverview
import xyz.wraithnet.phantom.ui.home.isSberPayRedirect

@RunWith(AndroidJUnit4::class)
class NativePurchaseUiTest {
    @get:Rule val compose = createComposeRule()
    private class Store : PurchaseClient {
        var purchases = 0
        var resumes = 0
        var dismissals = 0
        var grantedSource: String? = null
        var recovered = false
        var expectRenewal = false
        var expectedPlan = 27
        var expectedAmount = "349.00"
        var renewalError: String? = null
        var draft: JSONObject? = null
        var checkoutUrl: String? = null
        var syncs = 0
        val catalog = JSONObject("""{"sales_enabled":true,"payment_available":true,"cards":[],"plans":[{"id":27,"name":"Проверочный тариф","devices":8,"traffic_gb":987,"durations":[{"days":45,"amount":"349.00"}]}]}""")
        override suspend fun sync(): JSONObject {
            syncs++
            return catalog
        }
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
            return JSONObject().put("access_id","owned").put("status","PENDING").put("payment_url", checkoutUrl)
        }
        override suspend fun resumeCheckout(): JSONObject? {
            if (draft == null) return null
            resumes++
            return JSONObject().put("access_id", "previous").put("status", "PENDING").put("payment_url", checkoutUrl)
        }
        override suspend fun dismissCheckout(requestId: String) {
            assertEquals(draft?.getString("request_id"), requestId)
            dismissals++
            draft = null
            catalog.remove("payment")
        }
        override fun pending() = draft
        override fun recoveryCode(access: String?) = ""
    }
    @Test fun sberPayRedirectRoutesOnlyTheBankDeepLink() {
        assertTrue(isSberPayRedirect(Uri.parse("sberpay://payment/confirmation")))
        assertTrue(isSberPayRedirect(Uri.parse("SBERPAY://payment/confirmation")))
        assertFalse(isSberPayRedirect(Uri.parse("https://yoomoney.ru/payments/confirmation")))
        assertFalse(isSberPayRedirect(Uri.parse("javascript:alert(1)")))
        assertFalse(isSberPayRedirect(Uri.parse("intent://arbitrary")))
    }

    @Test fun bankBrowserUsesSameSavedInvoiceAndRechecksWhenClosed() {
        val store = Store().apply { checkoutUrl = "https://yookassa.ru/checkout/fixture" }
        val browser = object : ActivityResultRegistry(), ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry get() = this
            var lastRequest = -1
            val launched = mutableListOf<Intent>()

            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                lastRequest = requestCode
                launched += contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides browser) {
                MaterialTheme { NativePurchaseScreen(back = {}, client = store) }
            }
        }
        compose.onNodeWithText("45 дней · 349 ₽").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 349 ₽").performScrollTo().performClick()
        compose.runOnIdle {
            val intent = browser.launched.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(store.checkoutUrl, intent.dataString)
            assertTrue(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
            assertTrue(intent.getBooleanExtra(CustomTabsIntent.EXTRA_SEND_TO_EXTERNAL_DEFAULT_HANDLER, false))
            assertEquals(1, store.purchases)
        }

        val readsBeforeReturn = compose.runOnIdle {
            store.syncs.also { browser.dispatchResult(browser.lastRequest, Activity.RESULT_OK, Intent()) }
        }
        compose.waitUntil(5_000) { store.syncs > readsBeforeReturn }
        compose.onNodeWithText("Подписка добавлена. Можно подключаться к VPN.").assertDoesNotExist()
        compose.onNodeWithText("Продолжить оплату").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(store.checkoutUrl, store.checkoutUrl), browser.launched.map { it.dataString })
            assertEquals(1, store.purchases)
            assertEquals(1, store.resumes)
        }
    }

    @Test fun actualServerOfferNeedsConsentAndDoesNotRequireCredentials() {
        val store = Store()
        compose.setContent { MaterialTheme { NativePurchaseScreen({},store) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Проверочный тариф").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Устройств: 8 · 987 ГБ / месяц").assertExists()
        compose.onNodeWithText("45 дней · 349 ₽").performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 349 ₽").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 349 ₽").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.purchases == 1 }
        compose.onNodeWithText("Продолжить оплату").assertExists()
        compose.onNodeWithText("Пароль").assertDoesNotExist()
        compose.onNodeWithText("Электронная почта").assertDoesNotExist()
        compose.onNodeWithText("Подписка добавлена. Можно подключаться к VPN.").assertDoesNotExist()
    }
    @Test fun purchaseEntryOpensBothCardsWithTheirTermsAlreadyVisible() {
        val store = Store().apply {
            expectedPlan = 16
            expectedAmount = "650.00"
            catalog.getJSONArray("plans").put(JSONObject(
                """{"id":16,"name":"Семейный","devices":6,"traffic_gb":1500,"durations":[{"days":30,"amount":"250.00"},{"days":90,"amount":"650.00"}]}"""
            ))
        }
        val open = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            if (open.value) NativePurchaseScreen({ open.value = false }, store)
            else SubscriptionOverview(false, openPurchase = { open.value = true }, openRecovery = {}) {
                Text("Подключённые устройства")
            }
        } }
        compose.onNodeWithText("Подключённые устройства").assertIsDisplayed()
        compose.onNodeWithText("Семейный").assertDoesNotExist()
        compose.onNodeWithText("Купить подписку Phantom").performClick()
        compose.onNodeWithText("45 дней · 349 ₽").assertExists()
        compose.onNodeWithText("Одна подписка для всей семьи").assertExists()
        compose.onNodeWithText("30 дней · 250 ₽").assertExists()
        compose.onNodeWithText("90 дней · 650 ₽").performScrollTo().performClick()
        compose.onNodeWithText("Семейный").assertIsDisplayed()
        compose.onNodeWithText("Оплатить · 650 ₽").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Оплатить · 650 ₽").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.purchases == 1 }
    }

    @Test fun dismissingAnUnpaidInvoiceRequiresConfirmation() {
        val store = Store().apply {
            draft = JSONObject("""{"request_id":"saved-order","mode":"new","days":90,"amount":"400.00"}""")
            catalog.put("payment", JSONObject().put("status", "PENDING")
                .put("payment_url", "https://yookassa.ru/checkout/fixture"))
        }
        compose.setContent { MaterialTheme { NativePurchaseScreen({}, store) } }
        compose.onNodeWithText("Убрать счёт").performScrollTo().performClick()
        compose.onNodeWithText("Оставить").performClick()
        compose.runOnIdle { assertEquals(0, store.dismissals) }
        compose.onNodeWithText("Убрать счёт").performScrollTo().performClick()
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("Убрать счёт")).performClick()
        compose.onNodeWithText("Счёт убран.").assertIsDisplayed()
        compose.onNodeWithText("Продолжить оплату").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, store.dismissals) }
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
        compose.onNodeWithText("Купить подписку Phantom").assertDoesNotExist()
        compose.waitUntil(5000) { eligible.grantedSource == source }
        compose.onNodeWithText("45 дней · 349 ₽").performScrollTo().performClick()
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
        compose.onNodeWithText("Новая подписка · 90 дней · 400 ₽").assertExists()
        compose.onNodeWithText("Оплатить · 349 ₽").assertDoesNotExist()
        compose.onNodeWithText("Продолжить оплату").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.resumes == 1 }
        compose.runOnIdle {
            assertNull(store.grantedSource)
            assertEquals(0, store.purchases)
        }
    }
}
