package xyz.wraithnet.phantom.data

import android.content.Context
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Existing Remnashop guest checkout and financial state; no on-device payment verdict. */
internal interface PurchaseClient {
    suspend fun sync(): JSONObject
    suspend fun renewal(source: String): JSONObject
    suspend fun recover(code: String): JSONObject
    suspend fun checkout(plan: JSONObject, duration: JSONObject, aid: String?, renew: Boolean): JSONObject
    suspend fun resumeCheckout(): JSONObject?
    suspend fun dismissCheckout(requestId: String)
    fun pending(): JSONObject?
    fun recoveryCode(access: String? = null): String
}

internal class PurchaseOfferChanged(message: String) : IOException(message)
internal class RenewalUnavailable(message: String) : IOException(message)

internal class NativeStore(context: Context,
    configureHttp: OkHttpClient.Builder.() -> Unit = {},
) : PurchaseClient {
    private val app = context.applicationContext
    private val vault = PurchaseVault(app)
    private var csrf = ""
    private var lastRecoverySession = ""
    companion object {
        private const val BASE = "https://go.veor.pro/guest/api"
        private val operation = Mutex()
        private val syncing = Mutex()
        internal fun paymentUrl(raw: String): String? = raw.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host in setOf("yoomoney.ru", "yookassa.ru") &&
                it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
        }?.toString()
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                if (url.host != "go.veor.pro") return emptyList()
                val saved = vault.read().optJSONObject("cookies") ?: return emptyList()
                return saved.keys().asSequence().mapNotNull { Cookie.parse(url, saved.getString(it)) }
                    .filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }.toList()
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (url.host != "go.veor.pro") return
                vault.update { root ->
                    val saved = root.optJSONObject("cookies") ?: JSONObject()
                    cookies.filter { it.hostOnly && it.secure && it.domain == "go.veor.pro" }
                        .forEach { saved.put(it.name, it.toString()) }
                    root.put("cookies", saved)
                }
            }
        }).apply(configureHttp).build()

    private suspend fun request(path: String, body: JSONObject? = null, bearer: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(BASE + path)
            .header("Cache-Control", "no-store").header("User-Agent", "Phantom-Android")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (body == null) builder.get() else builder
            .header("Origin", "https://go.veor.pro").header("X-Phantom-CSRF", csrf)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.let { PhantomHttp.readUtf8Limited(it.byteStream(), 512 * 1024) }.orEmpty()
            val result = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                // Only this explicit server verdict is guaranteed to precede invoice dispatch.
                if (result?.optString("reason") == "QUOTE_CHANGED")
                    throw PurchaseOfferChanged("Тариф изменился. Выберите актуальные условия и повторите оплату.")
                val detail = result?.optString("detail")?.takeIf { it.isNotBlank() }?.take(240)
                if (path == "/native/renewal" && response.code == 409) {
                    when (detail) {
                        "Для этой подписки магазин не содержит условий продления." ->
                            throw RenewalUnavailable("В магазине нет тарифа для продления этой подписки.")
                        "Ссылка относится к прежней подписке. Обновите её.",
                        "Тариф этой подписки сейчас недоступен для продления." ->
                            throw RenewalUnavailable(detail.orEmpty())
                    }
                }
                val fallback = when (response.code) {
                    401 -> "Доступ истёк. Введите код восстановления."
                    429 -> "Подождите немного и повторите действие."
                    else -> "Сервер временно недоступен. Сохранённая покупка не потеряна."
                }
                throw IOException(detail ?: fallback)
            }
            result ?: throw IOException("Не удалось прочитать ответ магазина.")
        }
    }
    private suspend fun bootstrap(): JSONObject = request("/bootstrap").also { csrf = it.getString("csrf") }
    override fun recoveryCode(access: String?): String = runCatching { vault.read().let { root ->
        if (access != null) root.optJSONObject("recovery")?.optString(access).orEmpty()
        else root.optJSONObject("draft")?.optString("recovery_code").orEmpty()
    } }.getOrDefault("")
    override fun pending(): JSONObject? = runCatching { vault.read().optJSONObject("draft") }.getOrNull()
    private suspend fun updateVault(change: (JSONObject) -> Unit) =
        withContext(Dispatchers.IO) { vault.update(change) }

    private suspend fun saveRecovery(aid: String, code: String) {
        updateVault { root ->
            val codes = root.optJSONObject("recovery") ?: JSONObject()
            codes.put(aid, code); root.put("recovery", codes)
        }
    }
    suspend fun catalog(): JSONObject = operation.withLock {
        var data = bootstrap()
        val codes = vault.read().optJSONObject("recovery")
        val present = data.getJSONArray("cards").objects().map { it.getString("access_id") }.toSet()
        var recovered = false
        val missing = if (lastRecoverySession != csrf) codes?.keys()?.asSequence()?.filter { it !in present }?.take(5)?.toList().orEmpty() else emptyList()
        lastRecoverySession = csrf
        missing.forEach { aid ->
            // Only recover after an expired/lost server session. Never repeatedly retry revoked codes.
            try { request("/recover", JSONObject().put("code", codes.getString(aid))); recovered = true }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: IOException) { /* explicit recovery/login remains available */ }
        }
        if (recovered) data = bootstrap()
        data
    }
    override suspend fun renewal(source: String): JSONObject = operation.withLock {
        val bearer = SubscriptionDevicesApi.shortUuid(source)
            ?: throw IOException("Эта подписка не поддерживает продление в Phantom.")
        bootstrap()
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val code = vault.read().optJSONObject("renewal_codes")?.optString(fingerprint).orEmpty()
            .ifBlank { PurchaseVault.newCode().also { value ->
                updateVault { root ->
                    val codes = root.optJSONObject("renewal_codes") ?: JSONObject()
                    codes.put(fingerprint,value); root.put("renewal_codes",codes)
                }
            } }
        val result = request("/native/renewal", JSONObject().put("recovery_code",code), bearer)
        if (result.optBoolean("recovery_code_accepted")) saveRecovery(result.getString("access_id"),code)
        result
    }
    override suspend fun recover(code: String): JSONObject = operation.withLock {
        withContext(Dispatchers.IO) { vault.prepareRecovery() }
        bootstrap()
        val normalized = code.lowercase().replace("-", "").replace(" ", "").trim()
        val card = request("/recover", JSONObject().put("code", normalized))
        saveRecovery(card.getString("access_id"), normalized)
        card
    }
    /** Persist the immutable UUID/body before the first request, including uncertain outcomes. */
    override suspend fun checkout(
        plan: JSONObject, duration: JSONObject, aid: String?, renew: Boolean,
    ): JSONObject = operation.withLock {
        if (pending() != null) throw IOException("Сначала завершите сохранённый счёт.")
        require(!renew || aid != null) { "Не удалось определить подписку для продления." }
        bootstrap()
        if (aid != null) {
            val dismissed = vault.read().optJSONObject("dismissed_intents")
            for (iid in dismissed?.keys()?.asSequence().orEmpty()) {
                if (dismissed?.optString(iid) != aid) continue
                val invoice = state(iid)
                val status = invoice.optString("status")
                check(status == "CANCELED" || (status == "COMPLETED" && invoice.optString("delivery") == "DONE")) {
                    "Предыдущий счёт по этой подписке ещё открыт. Дождитесь его закрытия в ЮKassa."
                }
            }
        }
        val draft = JSONObject().put("plan_id", plan.getInt("id"))
            .put("days", duration.getInt("days")).put("amount", duration.getString("amount"))
            .put("mode", if (renew) "renew" else "new").put("request_id", UUID.randomUUID().toString())
            .put("recovery_code", if (aid == null) PurchaseVault.newCode() else "")
            .put("accepted_terms", true).put("native_app", true)
        if (aid != null) draft.put("access_id", aid)
        updateVault { it.put("draft", draft) }
        submitCheckout(draft)
    }

    override suspend fun resumeCheckout(): JSONObject? = operation.withLock {
        val draft = pending() ?: return@withLock null
        bootstrap()
        submitCheckout(draft)
    }

    override suspend fun dismissCheckout(requestId: String) = operation.withLock {
        val saved = vault.read()
        val draft = saved.optJSONObject("draft") ?: return@withLock
        check(draft.optString("request_id") == requestId) { "Счёт изменился. Проверьте его статус." }
        val iid = saved.optString("pending_intent").ifBlank {
            throw IOException("Создание счёта ещё не подтверждено. Проверьте оплату.")
        }
        val status = state(iid).optString("status")
        // A local PENDING record can outlive its checkout URL. Retain it until the
        // server confirms cancellation or delivers the paid subscription.
        check(status == "CANCELED" || status == "PENDING") {
            "Проверьте оплату перед тем, как убрать счёт."
        }
        updateVault { root ->
            if (status == "PENDING") {
                val dismissed = root.optJSONObject("dismissed_intents") ?: JSONObject()
                dismissed.put(iid, draft.optString("access_id"))
                root.put("dismissed_intents", dismissed)
            }
            root.remove("draft")
            root.remove("pending_intent")
        }
        Unit
    }

    /** A browser left open can still pay a dismissed invoice. Keep it until YooKassa settles it. */
    suspend fun syncDismissedCheckouts() = syncing.withLock {
        val ids = vault.read().optJSONObject("dismissed_intents")?.keys()?.asSequence()?.toList().orEmpty()
        if (ids.isEmpty()) return@withLock
        catalog()
        for (iid in ids) {
            val result = try { state(iid) } catch (_: IOException) { continue }
            val terminal = when (result.optString("status")) {
                "CANCELED" -> true
                "COMPLETED" -> result.optString("delivery") == "DONE" &&
                    result.optJSONObject("subscription")?.let { importActive(it) } == true
                else -> false
            }
            if (terminal) updateVault { root -> root.optJSONObject("dismissed_intents")?.remove(iid) }
        }
    }

    private suspend fun submitCheckout(draft: JSONObject): JSONObject {
        try {
            val prepared = request("/prepare", draft)
            val access = prepared.getString("access_id")
            draft.optString("recovery_code").takeIf { it.isNotBlank() }?.let { saveRecovery(access, it) }
            val iid = prepared.getString("intent_id")
            updateVault { it.put("pending_intent", iid) }
            return request("/intents/$iid/checkout", JSONObject())
        } catch (changed: PurchaseOfferChanged) {
            // Only this explicit server outcome precedes all provider activity.
            updateVault { it.remove("draft"); it.remove("pending_intent") }
            throw changed
        }
    }
    suspend fun state(iid: String): JSONObject = request("/intents/${UUID.fromString(iid)}")
    override suspend fun sync(): JSONObject = syncing.withLock {
        val data = catalog()
        var importFailed = false
        val iid = vault.read().optString("pending_intent").takeIf { it.isNotBlank() }
        val result = iid?.let { state(it) }
        val paidSubscription = result?.optJSONObject("subscription")
        val paidDelivered = paidSubscription?.let { importActive(it) } == true
        if (paidSubscription?.optBoolean("active") == true && !paidDelivered) importFailed = true
        for (card in data.getJSONArray("cards").objects()) {
            val sub = card.optJSONObject("subscription") ?: continue
            if (sub.optString("url") == paidSubscription?.optString("url") || !sub.optBoolean("active")) continue
            if (!importActive(sub)) importFailed = true
        }
        if (result != null) {
            if (result.optString("status") == "CANCELED" ||
                (result.optString("status") == "COMPLETED" && result.optString("delivery") == "DONE" && paidDelivered)) {
                operation.withLock {
                    updateVault {
                        if (it.optString("pending_intent") == iid) {
                            it.remove("draft")
                            it.remove("pending_intent")
                        }
                    }
                }
            }
            data.put("payment", result)
        }
        data.put("subscription_import_pending", importFailed)
        data
    }
    private suspend fun importActive(subscription: JSONObject): Boolean = try {
        deliver(subscription)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
        // A failed download must not hide the catalogue or discard a paid order.
        false
    }

    private suspend fun deliver(subscription: JSONObject): Boolean = withContext(Dispatchers.IO) {
        if (!subscription.optBoolean("active")) return@withContext false
        val source = subscription.optString("url").takeIf { it.startsWith("https://") } ?: return@withContext false
        val ownerToken = SubscriptionDevicesApi.shortUuid(source)
        fun ownsSubscription(sub: Subscription): Boolean =
            sub.source == source || (ownerToken != null && SubscriptionDevicesApi.shortUuid(sub.source) == ownerToken)
        val store = SubscriptionStore(app)
        val existing = store.read().firstOrNull(::ownsSubscription)
        val marker = subscription.getString("expire_at")
        if (existing != null && vault.read().optJSONObject("imported")?.optString(existing.id) == marker) {
            return@withContext true
        }
        val downloaded = SubscriptionFetcher.download(app, existing?.source ?: source, store.deviceId())
        val parsed = SubscriptionParser.parse(downloaded.body, downloaded.title)
        require(parsed.servers.isNotEmpty())
        val saved = parsed.copy(source = source, updateIntervalSeconds = downloaded.updateIntervalSeconds ?: 3600L,
            uploadBytes = downloaded.uploadBytes, downloadBytes = downloaded.downloadBytes,
            totalBytes = downloaded.totalBytes, expiresAt = downloaded.expiresAt,
            announcement = downloaded.announcement, supportUrl = downloaded.supportUrl,
            webPageUrl = downloaded.webPageUrl, accountTitle = downloaded.accountTitle)
        val result = store.update { current ->
            if (existing != null) SubscriptionMutations.replaceDownloaded(current, existing, saved, strict = true)
            else {
                check(current.none(::ownsSubscription))
                current + saved
            }
        }.first(::ownsSubscription)
        vault.update { root ->
            val imports = root.optJSONObject("imported") ?: JSONObject()
            imports.put(result.id, marker)
            root.put("imported", imports)
        }
        true
    }

}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
