package xyz.wraithnet.phantom.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PurchaseVaultTest {
    @Test fun sessionAndRecoverySurviveReopenAndAreNotStoredAsPlainText() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String,mode: Int) = super.getSharedPreferences("purchase-test-$name",mode)
        }
        val prefs = context.getSharedPreferences("phantom_purchases",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val code = PurchaseVault.newCode()
            assertEquals(64,code.length)
            PurchaseVault(context).update { it.put("recovery",code).put("order","durable-order-id") }
            assertFalse(prefs.getString("vault","")!!.contains(code))
            assertFalse(prefs.getString("vault","")!!.contains("durable-order-id"))
            val reopened = PurchaseVault(context).read()
            assertEquals(code,reopened.getString("recovery"))
            assertEquals("durable-order-id",reopened.getString("order"))
            val blob = android.util.Base64.decode(prefs.getString("vault","")!!,android.util.Base64.NO_WRAP)
            blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
            prefs.edit().putString("vault",android.util.Base64.encodeToString(blob,android.util.Base64.NO_WRAP)).commit()
            assertTrue(runCatching { PurchaseVault(context).read() }.isFailure)
            assertNotNull(prefs.getString("vault",null))
        } finally { prefs.edit().clear().commit() }
    }
}
