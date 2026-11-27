package xyz.wraithnet.phantom.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private subscription snapshots; the device's key is never shipped in the APK. */
internal class SubscriptionCipher {
    companion object {
        private const val ALIAS = "phantom-subscriptions-v1"
        private const val PREFIX = "encrypted:v1:"
        fun isEncrypted(value: String) = value.startsWith(PREFIX)
    }
    private var cached: SecretKey? = null
    private fun key(): SecretKey = cached ?: run {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }.also { cached = it }
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        cipher.updateAAD(ALIAS.toByteArray())
        return PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        if (!isEncrypted(value)) return value // Validated legacy data is migrated by SubscriptionStore.
        val blob = Base64.decode(value.removePrefix(PREFIX),Base64.NO_WRAP)
        require(blob.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,blob.copyOfRange(0,12)))
        cipher.updateAAD(ALIAS.toByteArray())
        return String(cipher.doFinal(blob.copyOfRange(12,blob.size)),Charsets.UTF_8)
    }
}
