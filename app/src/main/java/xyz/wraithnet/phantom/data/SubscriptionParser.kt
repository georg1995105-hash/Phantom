package xyz.wraithnet.phantom.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

object SubscriptionParser {
    fun parse(input: String, title: String? = null): Subscription {
        val normalized = input.trim()
        require(normalized.isNotEmpty()) { "Вставьте ссылку или конфигурацию" }

        val decoded = decodeIfBase64(normalized)
        val servers = when {
            decoded.startsWith("[") -> parseJsonArray(JSONArray(decoded))
            decoded.startsWith("{") -> parseJsonObject(JSONObject(decoded))
            else -> parseLinks(decoded)
        }
        require(servers.isNotEmpty()) { "Не найдено ни одного поддерживаемого сервера" }

        return Subscription(
            id = UUID.randomUUID().toString(),
            title = title ?: inferTitle(servers),
            source = normalized,
            servers = servers,
        )
    }

    private fun parseLinks(input: String): List<ServerProfile> = input
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapIndexedNotNull { index, line -> parseLink(line, index) }
        .toList()

    private fun parseLink(line: String, index: Int): ServerProfile? {
        val scheme = line.substringBefore(":", "").lowercase()
        val supported = setOf("vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2", "tuic", "socks", "http")
        if (scheme !in supported) return null
        val uri = runCatching { URI(line) }.getOrNull()
        val fragment = uri?.fragment?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val host = uri?.host ?: ""
        return ServerProfile(
            id = "$scheme-$index-${line.hashCode()}",
            name = fragment?.takeIf { it.isNotBlank() } ?: host.ifBlank { "Сервер ${index + 1}" },
            scheme = scheme,
            address = host,
            port = uri?.port?.takeIf { it > 0 },
            raw = line,
            countryFlag = extractFlag(fragment),
        )
    }

    private fun parseJsonArray(array: JSONArray): List<ServerProfile> = buildList {
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            when (value) {
                is JSONObject -> add(parseJsonConfig(value, index))
                is String -> parseLink(value, index)?.let(::add)
            }
        }
    }

    private fun parseJsonObject(root: JSONObject): List<ServerProfile> {
        val array = root.optJSONArray("configs") ?: root.optJSONArray("outbounds")
        if (array != null) return parseJsonArray(array)
        return listOf(parseJsonConfig(root, 0))
    }

    private fun parseJsonConfig(config: JSONObject, index: Int): ServerProfile {
        val remark = config.optString("remarks")
            .ifBlank { config.optString("name") }
            .ifBlank { config.optString("tag") }
            .ifBlank { "Сервер ${index + 1}" }
        val outbound = config.optJSONArray("outbounds")?.optJSONObject(0) ?: config
        val type = outbound.optString("protocol").ifBlank { outbound.optString("type", "xray") }
        val address = outbound.optString("address").ifBlank { outbound.optString("server") }
        return ServerProfile(
            id = "json-$index-${remark.hashCode()}",
            name = remark,
            scheme = type,
            address = address,
            port = outbound.optInt("port").takeIf { it > 0 },
            raw = config.toString(),
            countryFlag = extractFlag(remark),
        )
    }

    private fun decodeIfBase64(value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://") || value.contains("://")) return value
        val compact = value.replace("\\s".toRegex(), "")
        return runCatching {
            Base64.decode(compact, Base64.DEFAULT).toString(Charsets.UTF_8).trim()
        }.getOrDefault(value)
    }

    private fun inferTitle(servers: List<ServerProfile>): String = servers.first().name

    private fun extractFlag(value: String?): String {
        if (value == null) return ""
        return value.firstOrNull { it.code in 0x1F1E6..0x1F1FF }?.toString().orEmpty()
    }
}
