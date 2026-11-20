package xyz.wraithnet.phantom.data

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * URI share links use percent encoding, not application/x-www-form-urlencoded.
 * URLDecoder otherwise turns literal '+' in VPN passwords into spaces.
 */
object ShareLinkCodec {
    fun percentDecode(raw: String): String =
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")

    fun base64Text(raw: String): String {
        val text = raw.trim().replace(Regex("\\s"), "")
        val bytes = runCatching { Base64.getDecoder().decode(text) }
            .getOrElse { Base64.getUrlDecoder().decode(text) }
        return bytes.toString(Charsets.UTF_8)
    }

    fun vmess(raw: String): JSONObject =
        JSONObject(base64Text(raw.substringAfter("://").substringBefore('#')))

    data class Shadowsocks(
        val method: String,
        val password: String,
        val host: String,
        val port: Int,
    )

    /** Both SIP002 ss://BASE64(method:password)@host:port and legacy all-base64. */
    fun shadowsocks(raw: String): Shadowsocks {
        require(raw.startsWith("ss://", ignoreCase = true)) { "Неверная ссылка Shadowsocks" }
        val body = raw.substringAfter("://").substringBefore('#').substringBefore('?')
        val split = if ('@' in body) body else base64Text(body)
        val at = split.lastIndexOf('@')
        require(at > 0) { "Отсутствует адрес сервера Shadowsocks" }

        val rawCredentials = split.substring(0, at)
        val creds = if (':' in rawCredentials) percentDecode(rawCredentials)
            else base64Text(percentDecode(rawCredentials))
        val separator = creds.indexOf(':')
        require(separator > 0) { "Не указан метод шифрования Shadowsocks" }
        val method = creds.substring(0, separator)
        val password = creds.substring(separator + 1)
        require(password.isNotBlank()) { "Не указан пароль Shadowsocks" }

        val hostPort = percentDecode(split.substring(at + 1))
        val uri = URI("ss://$hostPort")
        val host = requireNotNull(uri.host) { "Не указан хост Shadowsocks" }
            .removePrefix("[").removeSuffix("]")
        val port = uri.port
        require(port in 1..65535) { "Неверный порт Shadowsocks" }
        return Shadowsocks(method, password, host, port)
    }
}
