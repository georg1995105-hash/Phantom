package xyz.wraithnet.phantom.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Device management scoped to the private URL of each Remnawave subscription.
 * No browser, Telegram account, shop transaction or panel token. Never log the
 * subscriber credential, put it in a query string or allow HTTP redirects. */
object SubscriptionDevicesApi {
    private const val URL = "https://go.veor.pro/guest/api/native/devices"
    private val idPattern = Regex("[0-9a-f]{64}")
    private val tokenPattern = Regex("[A-Za-z0-9_-]{12,64}")
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS).followRedirects(false)
            .followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    data class Device(
        val id: String, val platform: String?, val model: String?,
        val osVersion: String?, val app: String?, val addedAt: String?,
        val seenAt: String?, val current: Boolean,
    )
    data class Snapshot(
        val title: String, val count: Int, val limit: Int?,
        val canDelete: Boolean, val devices: List<Device>,
        val sessionsRefreshed: Boolean? = null,
        val canDeleteAll: Boolean = false,
    )
    internal fun shortUuid(source: String): String? =
        if (SubscriptionFetcher.usesProviderJson(source))
            source.toHttpUrlOrNull()?.pathSegments?.firstOrNull()
                ?.takeIf(tokenPattern::matches)
        else null

    fun supports(source: String) = shortUuid(source) != null

    suspend fun list(source: String, localHwid: String): Snapshot = request(source, localHwid, null)

    suspend fun remove(source: String, localHwid: String, id: String): Snapshot {
        require(idPattern.matches(id)) { "Некорректный идентификатор устройства" }
        return try {
            request(source, localHwid, id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (failure is HttpFailure) throw failure
            // Deleting this very phone may break the tunnel before the HTTP
            // response arrives. Re-read from the server instead of claiming
            // failure/success based solely on a lost response.
            val fresh = try { request(source, localHwid, null) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { throw failure }
            if (fresh.devices.none { it.id == id }) fresh else throw failure
        }
    }

    suspend fun removeAll(source: String, localHwid: String, ids: List<String>): Snapshot {
        require(ids.isNotEmpty() && ids.size <= 150 && ids.distinct().size == ids.size &&
            ids.all(idPattern::matches)) { "Некорректный список устройств" }
        return try {
            request(source, localHwid, null, ids)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (failure is HttpFailure) throw failure
            val fresh = try { list(source, localHwid) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { throw failure }
            if (fresh.devices.none { it.id in ids }) fresh else throw failure
        }
    }

    private class HttpFailure(val status: Int, message: String) : IOException(message)

    private suspend fun request(
        source: String, localHwid: String, remove: String?, removeAll: List<String>? = null
    ): Snapshot {
        val short = shortUuid(source)
            ?: throw IOException("Эта подписка не поддерживает управление устройствами.")
        val endpoint = when {
            removeAll != null -> "$URL/delete-all"
            remove != null -> "$URL/delete"
            else -> URL
        }
        val builder = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $short")
            .header("User-Agent", "Phantom-Android")
            .header("X-Phantom-HWID", localHwid.take(128))
            .header("Cache-Control", "no-store")
        if (remove == null && removeAll == null) builder.get()
        else {
            val payload = if (removeAll != null) JSONObject().put("device_ids", JSONArray(removeAll))
                else JSONObject().put("device_id", remove)
            builder.header("Origin", "https://go.veor.pro")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw HttpFailure(it.code, errorMessage(it.code))
                            val body = it.body ?: throw IOException("Пустой ответ сервера")
                            parse(PhantomHttp.readUtf8Limited(body.byteStream(), 192 * 1024))
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun errorMessage(status: Int) = when (status) {
        400, 401 -> "Адрес подписки не подтверждён сервером. Обновите подписку."
        403 -> "Удаление устройств для этой подписки запрещено."
        404 -> "Устройство уже отсутствует. Обновите список."
        408 -> "Сервер не ответил вовремя. Повторите запрос."
        429 -> "Превышено число запросов. Повторите позднее."
        409 -> "Список устройств изменился или операция недоступна. Обновите список."
        in 500..599 -> "Сервер устройств временно недоступен. Повторите позже."
        else -> "Операция не выполнена: HTTP $status."
    }

    internal fun parse(json: String): Snapshot {
        val root = JSONObject(json)
        val arr = root.getJSONArray("devices")
        require(arr.length() <= 150) { "Некорректный размер списка устройств" }
        val devices = (0 until arr.length()).map { index ->
            val item = arr.getJSONObject(index)
            val id = item.getString("id")
            require(idPattern.matches(id)) { "Некорректный ответ сервера устройств" }
            fun opt(key: String) = item.optString(key).trim()
                .takeIf { it.isNotBlank() && it != "null" }?.take(100)
            Device(
                id = id, platform = opt("platform"), model = opt("model"),
                osVersion = opt("os_version"), app = opt("app"),
                addedAt = opt("added_at"), seenAt = opt("seen_at"),
                current = item.optBoolean("is_current", false)
            )
        }
        val count = root.getInt("count")
        require(count == devices.size) { "Список устройств изменился. Обновите его." }
        val title = root.optString("title").trim().take(100).ifBlank { "Phantom VPN" }
        return Snapshot(title, count, root.optInt("limit").takeIf { it > 0 },
            root.optBoolean("delete_enabled", false), devices,
            root.optBoolean("sessions_refreshed").takeIf { root.has("sessions_refreshed") },
            root.optBoolean("delete_all_enabled", false))
    }
}
