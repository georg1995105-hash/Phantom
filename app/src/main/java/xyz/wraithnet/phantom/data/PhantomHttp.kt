package xyz.wraithnet.phantom.data

import android.content.Context
import xyz.wraithnet.phantom.service.VpnRuntimeBridge
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Reusable HTTP stack for subscriptions, periodic refresh and APK updates. */
object PhantomHttp {
    const val DNS_FALLBACK_KEY = "http_dns_fallback"
    private val lock = Any()
    @Volatile private var shared: OkHttpClient? = null
    @Volatile private var activeRoute: String? = null

    data class NetworkState(
        val routeId: String?,
        val validated: Boolean,
        val otherVpn: Boolean,
    )

    internal fun explainFailure(error: IOException, network: NetworkState): String {
        if (network.otherVpn && (error is UnknownHostException ||
                    error is ConnectException || error is SocketTimeoutException)) {
            return "Сеть сейчас работает через другой VPN. Отключите его, " +
                "дождитесь восстановления интернета и повторите попытку. " +
                "Android не запускает два VPN одновременно."
        }
        return describe(error, network.validated)
    }

    private fun networkState(context: Context): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkState(null, true, false)
        return runCatching {
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val ownVpn = VpnRuntimeBridge.snapshot(context).running
            NetworkState(
                routeId = network?.toString(),
                validated = caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                otherVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    !ownVpn,
            )
        }.getOrDefault(NetworkState(null, true, false))
    }

    private fun ip(vararg bytes: Int): InetAddress =
        InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())

    private fun createClient(context: Context): OkHttpClient {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

        // Two independent, TLS-validated DoH providers with literal bootstrap
        // addresses; name resolution doesn't need the already-broken system DNS.
        val cloudflare = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(ip(1, 1, 1, 1), ip(1, 0, 0, 1))
            .build()
        val google = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(ip(8, 8, 8, 8), ip(8, 8, 4, 4))
            .build()

        val app = context.applicationContext
        val dns = ResilientDns(
            systemDns = Dns.SYSTEM,
            encryptedFallbacks = listOf(cloudflare, google),
            allowFallback = {
                app.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
                    .getBoolean(DNS_FALLBACK_KEY, true) &&
                    !hasStrictPrivateDns(app) &&
                    // Do not bypass DNS policy defined by a different VPN.
                    !networkState(app).otherVpn
            }
        )

        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(false) // Never downgrade HTTPS to plaintext HTTP.
            .build()
    }

    private fun client(context: Context): OkHttpClient {
        val route = networkState(context).routeId
        val current = shared
        if (current != null && route == activeRoute) return current
        return synchronized(lock) {
            val old = shared
            if (old != null && route == activeRoute) return@synchronized old
            // When Android switches INCY -> Phantom or VPN -> mobile/Wi-Fi,
            // discard pooled sockets and DNS caches bound to the old network.
            val fresh = createClient(context)
            activeRoute = route
            shared = fresh
            old?.connectionPool?.evictAll()
            fresh
        }
    }

    /**
     * A manual refresh can explicitly use the active profile's localhost HTTP
     * inbound. The app UID is excluded from TUN; a regular OkHttp request would
     * instead use the physical network and falsely suggest that the selected
     * country is working. Never retry these requests on the direct client.
     */
    private fun subscriptionClient(context: Context, route: ActiveVpnSubscriptionProxy.Route?): OkHttpClient {
        if (route == null) return client(context)
        return client(context).newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", route.port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic("phantom", route.password))
                    .build()
            }
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    private fun hasStrictPrivateDns(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            val active = cm.activeNetwork ?: return@runCatching false
            !cm.getLinkProperties(active)?.privateDnsServerName.isNullOrBlank()
        }.getOrDefault(false)
    }

    /** No credentials are logged; raw URLs may contain subscriber secrets. */
    fun describe(error: IOException, validatedNetwork: Boolean): String = when (error) {
        is UnknownHostException -> if (!validatedNetwork)
            "Нет доступа к интернету или DNS. Проверьте Wi-Fi, мобильную сеть и частный DNS Android."
            else "Не удалось определить адрес сервера. Проверьте частный DNS и доступность домена."
        is SocketTimeoutException -> "Сервер не ответил вовремя. Проверьте сеть и повторите попытку."
        is ConnectException, is SocketException ->
            "Не удалось установить соединение. Возможно, сеть ограничивает доступ к серверу."
        is SSLException ->
            "Ошибка проверки защищённого соединения HTTPS. Проверьте дату, сеть и сертификат."
        else -> "Ошибка сети. Проверьте подключение и повторите попытку."
    }

    private fun retryable(error: IOException): Boolean =
        error is UnknownHostException || error is SocketTimeoutException ||
            error is ConnectException || error is SocketException

    class Failure(message: String, cause: IOException) : IOException(message, cause)

    internal suspend fun <T> get(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMs: Long = 45_000L,
        vpnRoute: ActiveVpnSubscriptionProxy.Route? = null,
        manual: Boolean = false,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val maxAttempts = if (manual) 1 else 3
        for (attempt in 0 until maxAttempts) {
            if (vpnRoute != null && !ActiveVpnSubscriptionProxy.isActive(context, vpnRoute)) {
                throw IOException("Выбранная нода VPN отключена или изменена")
            }
            // No silent retries on manual checks: the whole operation must
            // finish promptly, including slow TCP, TLS, redirects and body read.
            val baseClient = subscriptionClient(context, vpnRoute)
            val requestClient = if (manual) baseClient.newBuilder()
                .retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build() else baseClient
            val call = requestClient.newCall(request)
            if (callTimeoutMs > 0L) call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (attempt + 1 < maxAttempts && retryable(e)) {
                    // The network/DNS may not yet be ready after a VPN switch.
                    delay(if (attempt == 0) 450L else 1200L)
                    continue
                }
                throw Failure(explainFailure(e, networkState(context)), e)
            }
            response.use {
                if (vpnRoute != null && !ActiveVpnSubscriptionProxy.isActive(context, vpnRoute)) {
                    throw IOException("Выбранная нода VPN отключена или изменена")
                }
                if (!it.isSuccessful) throw IOException("Сервер ответил HTTP " + it.code)
                if (request.url.isHttps && !it.request.url.isHttps) {
                    throw IOException("Небезопасная переадресация HTTPS отклонена")
                }
                return@withContext read(it)
            }
        }
        error("Повторные попытки соединения исчерпаны")
    }

    /** Strict bound prevents huge subscription/release JSON from exhausting memory. */
    fun readUtf8Limited(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0)
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            require(result.size().toLong() + count <= maxBytes) {
                "Ответ сервера слишком большой"
            }
            result.write(buffer, 0, count)
        }
        return result.toString(Charsets.UTF_8.name())
    }
}
