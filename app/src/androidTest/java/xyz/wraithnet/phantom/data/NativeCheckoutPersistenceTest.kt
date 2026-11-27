package xyz.wraithnet.phantom.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native client/vault contract, synthetic HTTP only; never contacts production. */
@RunWith(AndroidJUnit4::class)
class NativeCheckoutPersistenceTest {
    @Test fun changedOfferCanBeRechosenWhileUnknownPaymentKeepsItsImmutableIdentity() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String,mode: Int) = super.getSharedPreferences("checkout-test-$name",mode)
        }
        val prefs = context.getSharedPreferences("phantom_purchases",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var rejectQuote = true
        var loseCheckout = true
        var intent: String? = null
        var frozen: String? = null
        var checkouts = 0
        var paid = false
        val transport = Interceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            var status = 200
            val body = when {
                path.endsWith("/bootstrap") -> {
                    val cards = JSONArray()
                    if (intent != null) cards.put(JSONObject().put("access_id", "fixture-access"))
                    JSONObject().put("csrf", "fixture-csrf").put("cards", cards).put("plans", JSONArray()).toString()
                }
                path.endsWith("/prepare") -> {
                    assertEquals("https://go.veor.pro",request.header("Origin"))
                    assertEquals("fixture-csrf",request.header("X-Phantom-CSRF"))
                    val sent = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    val draft = JSONObject(sent)
                    assertNotNull(PurchaseVault(context).read().optJSONObject("draft"))
                    if (rejectQuote) {
                        status = 409
                        """{"detail":"Offer changed","reason":"QUOTE_CHANGED"}"""
                    } else {
                        if (frozen == null) { frozen = sent; intent = draft.getString("request_id") }
                        else assertEquals(frozen,sent)
                        JSONObject().put("intent_id",intent).put("access_id","fixture-access").toString()
                    }
                }
                path.endsWith("/checkout") -> {
                    checkouts++
                    assertTrue(path.contains(intent!!))
                    assertEquals(intent,PurchaseVault(context).read().getString("pending_intent"))
                    if (loseCheckout) { status = 503; """{"detail":"Provider verdict unknown"}""" }
                    else JSONObject().put("intent_id",intent).put("access_id","fixture-access").put("status","PENDING").toString()
                }
                path.endsWith("/intents/$intent") -> {
                    if (!paid) """{"status":"PENDING"}"""
                    else """{"status":"COMPLETED","delivery":"DONE","subscription":{"url":"http://example.invalid","active":true}}"""
                }
                else -> error("Unexpected client endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        fun client() = NativeStore(context) { addInterceptor(transport) }
        val plan = JSONObject().put("id",15)
        val first = JSONObject().put("days",30).put("amount","150.00")
        try {
            val store = client()
            assertTrue(runCatching { store.checkout(plan,first,null,false) }.exceptionOrNull() is PurchaseOfferChanged)
            assertNull(store.pending())
            assertFalse(PurchaseVault(context).read().has("pending_intent"))
            assertEquals(0,checkouts)
            rejectQuote = false
            assertTrue(runCatching { store.checkout(plan,first,null,false) }.isFailure)
            val pending = store.pending()!!
            assertEquals(intent,pending.getString("request_id"))
            assertEquals("150.00",pending.getString("amount"))
            assertEquals(1,checkouts)
            // Re-open the app with another visible term: uncertain payment must keep the OLD request.
            loseCheckout = false
            val reopened = client()
            val otherTerm = JSONObject().put("days", 90).put("amount", "400.00")
            assertTrue(runCatching { reopened.checkout(plan, otherTerm, "another-owner", true) }.isFailure)
            assertEquals(1, checkouts)
            val result = requireNotNull(reopened.resumeCheckout())
            assertEquals(intent, result.getString("intent_id"))
            assertEquals(pending.toString(),reopened.pending().toString())
            assertEquals(2,checkouts) // Same server intent both times; native server tests prove provider idempotence.
            assertEquals("PENDING", reopened.sync().getJSONObject("payment").getString("status"))
            assertNotNull(reopened.pending())
            paid = true
            val confirmed = reopened.sync()
            assertEquals("COMPLETED", confirmed.getJSONObject("payment").getString("status"))
            assertTrue(confirmed.getBoolean("subscription_import_pending"))
            assertEquals(pending.toString(), reopened.pending().toString())
            assertEquals(2, checkouts)
        } finally { prefs.edit().clear().commit() }
    }
}
