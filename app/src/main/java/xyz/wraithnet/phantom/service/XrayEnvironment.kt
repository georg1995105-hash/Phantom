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

    fun initialize(context: Context, probeOnly: Boolean = false) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext
            val assets = File(app.filesDir, "xray-assets").apply { mkdirs() }
            for (name in listOf("geoip.dat", "geosite.dat")) {
                val target = File(assets, name)
                if (!target.exists() || target.length() < 1024) {
                    val temp = File.createTempFile(name, ".tmp", assets)
                    try {
                        app.assets.open(name).use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        require(temp.renameTo(target)) { "Не удалось подготовить $name" }
                    } finally {
                        temp.delete()
                    }
                }
            }
            Seq.setContext(app)
            Libv2ray.initCoreEnv(assets.absolutePath, if (probeOnly) newXudpKey() else getOrCreateXudpKey(app))
            initialized = true
        }
    }

    private fun getOrCreateXudpKey(context: Context): String {
        val prefs = context.getSharedPreferences("phantom_core", Context.MODE_PRIVATE)
        val current = prefs.getString("xudp_base_key", null)
        if (current != null && isValidKey(current)) return current
        val created = newXudpKey()
        check(isValidKey(created)) { "Ошибка генерации ключа XUDP" }
        check(prefs.edit().putString("xudp_base_key", created).commit()) {
            "Не удалось сохранить ключ XUDP"
        }
        return created
    }

    private fun newXudpKey(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    internal fun isValidKey(value: String): Boolean =
        runCatching { Base64.getUrlDecoder().decode(value).size == 32 }
            .getOrDefault(false)
}
