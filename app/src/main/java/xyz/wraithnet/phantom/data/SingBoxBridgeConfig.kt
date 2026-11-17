package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Real sing-box SOCKS5 → TUIC / AnyTLS bridge behind Android's existing Xray TUN. */
object SingBoxBridgeConfig {
    val schemes = setOf("tuic", "anytls")

    fun requiresSingBox(profile: ServerProfile): Boolean =
        profile.scheme.lowercase() in schemes

    fun fromShareLink(raw: String, localPort: Int, resolvedServerIp: String? = null): String {
        require(localPort in 1024..65535) { "Неверный локальный порт" }
        val uri = URI(raw.trim())
        val protocol = uri.scheme?.lowercase() ?: ""
        require(protocol in schemes) { "Протокол не поддерживается sing-box" }
        val host = requireNotNull(uri.host) { "Нет адреса сервера" }
        require(uri.port in 1..65535) { "Неверный порт сервера" }

        val decodedUser = ShareLinkCodec.percentDecode(uri.rawUserInfo.orEmpty())
        require(decodedUser.isNotBlank()) { "Нет данных авторизации" }
        val query = query(uri.rawQuery.orEmpty())
        val sni = query["sni"] ?: query["peer"] ?: host
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", sni)
            .put("insecure", query["allow_insecure"] == "1" || query["insecure"] == "1")
        query["alpn"]?.takeIf { it.isNotBlank() }?.let {
            tls.put("alpn", JSONArray(it.split(",").map(String::trim).filter(String::isNotBlank)))
        }

        val outbound = JSONObject()
            .put("type", protocol)
            .put("tag", "proxy")
            .put("server", resolvedServerIp?.takeIf { it.isNotBlank() } ?: host)
            .put("server_port", uri.port)
            .put("tls", tls)
        when (protocol) {
            "tuic" -> {
                val separator = decodedUser.indexOf(':')
                require(separator > 0 && separator < decodedUser.lastIndex) {
                    "Ссылка TUIC должна содержать UUID и пароль"
                }
                val uuid = decodedUser.substring(0, separator)
                require(runCatching { UUID.fromString(uuid) }.isSuccess) {
                    "Неверный UUID TUIC"
                }
                outbound.put("uuid", uuid)
                    .put("password", decodedUser.substring(separator + 1))
                    .put("congestion_control", query["congestion_control"] ?: "cubic")
                    .put("zero_rtt_handshake", query["zero_rtt_handshake"] == "1")
            }
            "anytls" -> outbound.put("password", decodedUser)
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "socks")
                .put("tag", "local-socks")
                .put("listen", "127.0.0.1")
                .put("listen_port", localPort)))
            .put("outbounds", JSONArray().put(outbound))
            .put("route", JSONObject()
                .put("auto_detect_interface", true)
                .put("final", "proxy"))
            .toString()
    }

    private fun query(raw: String): Map<String, String> = raw.split("&")
        .filter { it.isNotBlank() }
        .associate {
            ShareLinkCodec.percentDecode(it.substringBefore("=")).lowercase() to
                ShareLinkCodec.percentDecode(it.substringAfter("=", ""))
        }
}
