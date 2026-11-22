package xyz.wraithnet.phantom.data

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Best-effort *exit* network identity, not a guess based on profile name, server IP
 * or device carrier. The app UID is outside TUN: ONLY use the active profile's
 * authenticated loopback proxy and NEVER fall back to a physical-network request.
 *
 * HTTPS endpoint returns the visible exit IP, ASN and registered network organization.
 * This is not proof of the VPS reseller/hosting brand.
 */
internal object ExitNetworkLookup {
    data class Info(
        val ip: String,
        val countryCode: String,
        val network: String,
        val asn: String,
    ) {
        fun countryName(): String = Locale.Builder().setRegion(countryCode).build()
            .getDisplayCountry(Locale.forLanguageTag("ru")).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.forLanguageTag("ru")) else it.toString()
            }

        fun flag(): String = countryCode.uppercase(Locale.ROOT).map {
            String(Character.toChars(0x1F1E6 + it.code - 'A'.code))
        }.joinToString("")
    }

    suspend fun query(context: Context, profileRaw: String): Info? {
        val route = ActiveVpnSubscriptionProxy.forProfile(profileRaw) ?: return null
        return runCatching {
            PhantomHttp.get(
                context = context,
                url = "https://ipwho.is/",
                vpnRoute = route,
                manual = true,
                callTimeoutMs = 7_000L,
                headers = mapOf("Accept" to "application/json"),
            ) { response ->
                val stream = response.body?.byteStream() ?: error("Пустой ответ")
                parse(stream.use { PhantomHttp.readUtf8Limited(it, 64 * 1024) })
            }
        }.getOrNull()
    }

    internal fun parse(body: String): Info? {
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) return null
        val ip = root.optString("ip").trim()
        // Validate only the shape; never resolve an arbitrary API-provided hostname.
        if (ip.isBlank() || ip.length > 45 || ip.any {
                !it.isDigit() && it != '.' && it != ':' && it !in 'a'..'f' && it !in 'A'..'F'
            }) return null
        val country = root.optString("country_code").uppercase(Locale.ROOT)
        if (!Regex("[A-Z]{2}").matches(country)) return null
        val connection = root.optJSONObject("connection") ?: return null
        val org = connection.optString("org").trim().takeUnless { it.equals("null", true) }.orEmpty()
        val isp = connection.optString("isp").trim().takeUnless { it.equals("null", true) }.orEmpty()
        val name = org.ifBlank { isp }.take(120)
        if (name.isBlank()) return null
        val number = connection.optInt("asn", 0)
        return Info(ip, country, name, if (number > 0) "AS$number" else "")
    }
}
