package xyz.wraithnet.phantom.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Purchases, recovery credentials and session cookies never enter backup/logs. */
internal class PurchaseVault(context: Context) {
    private val prefs = context.getSharedPreferences("phantom_purchases", Context.MODE_PRIVATE)
    companion object {
        private val lock = Any()
        private const val ALIAS = "phantom-purchases-v1"
        fun newCode(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private var cachedKey: SecretKey? = null
    private var cachedBlob: String? = null
    private var cachedJson: String? = null
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(ALIAS, null) as? SecretKey)?.let { cachedKey = it; return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey().also { cachedKey = it }
    }
    private fun readLocked(): JSONObject {
        val encoded = prefs.getString("vault", null) ?: return JSONObject()
        if (cachedBlob == encoded && cachedJson != null) return JSONObject(cachedJson!!)
        try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            cipher.updateAAD(ALIAS.toByteArray())
            val json = String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
            val decoded = JSONObject(json)
            cachedBlob = encoded; cachedJson = json
            return decoded
        } catch (_: Exception) {
            error("Не удалось открыть защищённые данные покупки. Используйте сохранённый код восстановления.")
        }
    }
    fun read(): JSONObject = synchronized(lock) { readLocked() }
    fun update(change: (JSONObject) -> Unit) = synchronized(lock) {
        val data = readLocked().also(change)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ALIAS.toByteArray())
        val blob = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("vault", Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            "Не удалось сохранить покупку. Повторите до перехода к оплате."
        }
    }
}
