package xyz.wraithnet.phantom.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

/**
 * One-time encrypted TV subscription transfer on the same local network.
 *
 * Only the TV LAN address, a random one-time token and a fresh RSA public key
 * appear in the QR code. Subscription URLs / credentials are never in QR,
 * HTTP, external relays, analytics, logs or clipboard.
 *
 * No external service is required. This mode intentionally does not claim
 * to work across different networks; that needs a separately reviewed relay.
 */
object TvSubscriptionPairing {
    private const val SCHEME = "phantom"
    private const val HOST = "pair"
    private const val TTL_MS = 120_000L
    private const val MAX_PACKET = 128 * 1024
    private const val MAX_SOURCE = 100 * 1024
    private const val IO_TIMEOUT_MS = 90_000
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    private fun b64(bytes: ByteArray): String = encoder.encodeToString(bytes)
    private fun un64(value: String): ByteArray = decoder.decode(value)

    fun isPairingCode(value: String): Boolean {
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return false
        return uri.scheme == SCHEME && uri.host == HOST
    }

    private data class Offer(
        val host: String,
        val port: Int,
        val token: ByteArray,
        val publicKey: ByteArray,
        val expiresAt: Long,
    )

    private fun offer(value: String): Offer {
        require(value.length in 100..4000 && isPairingCode(value)) { "Неверный QR-код подключения" }
        val uri = Uri.parse(value.trim())
        val host = requireNotNull(uri.getQueryParameter("ip"))
        // Reject hostnames and non-LAN addresses: pairing QR must never induce
        // a request to the internet or to arbitrary DNS names.
        require(host.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}"""))) { "Неверный адрес телевизора" }
        val ip = InetAddress.getByName(host)
        require(ip is Inet4Address && ip.isSiteLocalAddress) {
            "Телевизор должен быть в локальной сети"
        }
        val port = uri.getQueryParameter("port")?.toIntOrNull()
        require(port != null && port in 1024..65535) { "Неверный порт телевизора" }
        val token = un64(requireNotNull(uri.getQueryParameter("token")))
        require(token.size == 24) { "Неверный код сопряжения" }
        val key = un64(requireNotNull(uri.getQueryParameter("key")))
        require(key.size in 280..500) { "Неверный открытый ключ" }
        val expiresAt = uri.getQueryParameter("exp")?.toLongOrNull()
        require(expiresAt != null && expiresAt >= System.currentTimeMillis() &&
            expiresAt - System.currentTimeMillis() <= TTL_MS + 5_000L) {
            "QR-код устарел. Обновите его на телевизоре."
        }
        return Offer(host, port, token, key, expiresAt)
    }

    private fun physicalLan(context: Context): Pair<Network, String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Prefer Ethernet for wired Android TV, Wi-Fi for phones. Exclude VPN
        // interfaces so an active Phantom tunnel doesn't carry local credentials.
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@mapNotNull null
            val ip = cm.getLinkProperties(network)?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { it.isSiteLocalAddress } ?: return@mapNotNull null
            network to ip.hostAddress.orEmpty()
        }
        return candidates.firstOrNull()
            ?: error("Подключите телефон и телевизор к одной сети Wi-Fi или Ethernet")
    }

    class Listener internal constructor(
        val qrCode: String,
        private val token: ByteArray,
        private val keys: KeyPair,
        private val expiresAt: Long,
        private val socket: ServerSocket,
    ) : Closeable {
        override fun close() {
            runCatching { socket.close() }
        }

        /** Returns success only after the caller has persisted the subscription. */
        suspend fun receive(import: suspend (String, String?) -> Boolean): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val client = socket.accept()
                    client.use { connection ->
                        connection.soTimeout = IO_TIMEOUT_MS
                        val data = DataInputStream(connection.getInputStream())
                        val size = data.readInt()
                        require(size in 1..MAX_PACKET) { "Недопустимый размер запроса" }
                        val bytes = ByteArray(size)
                        data.readFully(bytes)
                        require(System.currentTimeMillis() <= expiresAt) {
                            "QR-код устарел"
                        }
                        val incoming = JSONObject(bytes.toString(Charsets.UTF_8))
                        require(incoming.optString("token") == b64(token)) {
                            "Неверная сессия сопряжения"
                        }

                        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
                        rsa.init(Cipher.DECRYPT_MODE, keys.private, OAEPParameterSpec(
                            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
                        val aesKey = rsa.doFinal(un64(incoming.getString("wrapped")))
                        require(aesKey.size == 32) { "Неверный ключ шифрования" }
                        val nonce = un64(incoming.getString("nonce"))
                        require(nonce.size == 12) { "Неверный одноразовый код" }
                        val aes = Cipher.getInstance("AES/GCM/NoPadding")
                        aes.init(Cipher.DECRYPT_MODE,
                            javax.crypto.spec.SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
                        aes.updateAAD(token)
                        val plaintext = aes.doFinal(un64(incoming.getString("ciphertext")))
                        require(plaintext.size in 1..MAX_SOURCE) { "Конфигурация слишком большая" }
                        val payload = JSONObject(plaintext.toString(Charsets.UTF_8))
                        val source = payload.getString("source").trim()
                        val title = payload.optString("title").takeIf { it.isNotBlank() }?.take(120)
                        require(source.isNotBlank() && source.length <= MAX_SOURCE) {
                            "Пустая или слишком большая подписка"
                        }
                        val success = import(source, title)
                        DataOutputStream(connection.getOutputStream()).use { reply ->
                            reply.writeByte(if (success) 1 else 0)
                            reply.flush()
                        }
                        success
                    }
                } finally {
                    close()
                }
            }
    }

    suspend fun listen(context: Context): Listener = withContext(Dispatchers.IO) {
        val (_, ip) = physicalLan(context)
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val token = ByteArray(24).also(random::nextBytes)
        // Only the selected private LAN interface can accept the one-time QR transfer.
        val server = ServerSocket(0, 1, InetAddress.getByName(ip))
            .apply { soTimeout = TTL_MS.toInt() }
        try {
            val expiresAt = System.currentTimeMillis() + TTL_MS
            val code = Uri.Builder()
                .scheme(SCHEME).authority(HOST)
                .appendQueryParameter("ip", ip)
                .appendQueryParameter("port", server.localPort.toString())
                .appendQueryParameter("token", b64(token))
                .appendQueryParameter("key", b64(keys.public.encoded))
                .appendQueryParameter("exp", expiresAt.toString())
                .build().toString()
            Listener(code, token, keys, expiresAt, server)
        } catch (error: Exception) {
            server.close()
            throw error
        }
    }

    suspend fun send(context: Context, qr: String, source: String, title: String?): Boolean =
        withContext(Dispatchers.IO) {
            val target = offer(qr)
            val payload = JSONObject().put("source", source.trim())
                .put("title", title.orEmpty().take(120)).toString().toByteArray(Charsets.UTF_8)
            require(payload.size in 1..MAX_SOURCE) { "Подписка слишком большая для передачи" }

            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(target.publicKey))
            val aesKey = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
            val nonce = ByteArray(12).also(random::nextBytes)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
            aes.updateAAD(target.token)
            val ciphertext = aes.doFinal(payload)
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            val wrapped = rsa.doFinal(aesKey.encoded)
            val bytes = JSONObject()
                .put("token", b64(target.token))
                .put("nonce", b64(nonce))
                .put("wrapped", b64(wrapped))
                .put("ciphertext", b64(ciphertext))
                .toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PACKET) { "Конфигурация слишком большая" }
            require(System.currentTimeMillis() <= target.expiresAt) { "QR-код устарел" }
            val (network, _) = physicalLan(context)
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), 7_000)
                socket.soTimeout = IO_TIMEOUT_MS
                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
                DataInputStream(socket.getInputStream()).readUnsignedByte() == 1
            }
        }
}
