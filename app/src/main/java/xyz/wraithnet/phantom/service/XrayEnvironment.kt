package xyz.wraithnet.phantom.service

import android.content.Context
import go.Seq
import libv2ray.Libv2ray
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Xray initCoreEnv's key argument is a base64url-encoded 32-byte XUDP key,
 * never an application identifier. It must remain stable across restarts.
 */
object XrayEnvironment {
    private val lock = Any()
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext
            val assets = File(app.filesDir, "xray-assets").apply { mkdirs() }
            for (name in listOf("geoip.dat", "geosite.dat")) {
                val target = File(assets, name)
                if (!target.exists() || target.length() < 1024) {
                    val temp = File(assets, "$name.tmp")
                    app.assets.open(name).use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (target.exists()) target.delete()
                    require(temp.renameTo(target)) { "Не удалось подготовить $name" }
                }
            }
            Seq.setContext(app)
            Libv2ray.initCoreEnv(assets.absolutePath, getOrCreateXudpKey(app))
            initialized = true
        }
    }

    private fun getOrCreateXudpKey(context: Context): String {
        val prefs = context.getSharedPreferences("phantom_core", Context.MODE_PRIVATE)
        val current = prefs.getString("xudp_base_key", null)
        if (current != null && isValidKey(current)) return current
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val created = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        check(isValidKey(created)) { "Ошибка генерации ключа XUDP" }
        check(prefs.edit().putString("xudp_base_key", created).commit()) {
            "Не удалось сохранить ключ XUDP"
        }
        return created
    }

    internal fun isValidKey(value: String): Boolean =
        runCatching { Base64.getUrlDecoder().decode(value).size == 32 }
            .getOrDefault(false)
}
