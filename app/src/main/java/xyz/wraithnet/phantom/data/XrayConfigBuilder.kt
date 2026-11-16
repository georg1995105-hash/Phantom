package xyz.wraithnet.phantom.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Builds an Xray TUN configuration from one Xray-compatible share link. */
object XrayConfigBuilder {
    fun build(profile: ServerProfile): String {
        val source = profile.raw.trim()
        if (source.startsWith("{")) return buildJson(source)
        require(profile.scheme.lowercase() in setOf("vless", "vmess", "trojan", "ss")) {
            "${profile.scheme.uppercase()} работает через sing-box и пока не подключён"
        }

        val outbound = when (profile.scheme.lowercase()) {
            "vless" -> vless(profile.raw)
            "vmess" -> vmess(profile.raw)
            "trojan" -> trojan(profile.raw)
            "ss" -> shadowsocks(profile.raw)
            else -> error("Неподдерживаемый Xray-протокол")
        }.put("tag", "proxy")

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(
                JSONObject()
                    .put("tag", "tun")
                    .put("port", 0)
                    .put("protocol", "tun")
                    .put("settings", JSONObject().put("mtu", 1500).put("userLevel", 8))
                    .put("sniffing", JSONObject()
                        .put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls").put("quic")),
                    ),
            ))
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("routing", JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", JSONArray().put(JSONObject()
                    .put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", "proxy"))))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .toString()
    }

    /** Preserve complete Remnawave JSON; only replace local inbounds with TUN. */
    private fun buildJson(raw: String): String {
        val input = JSONObject(raw)
        val root = if (input.has("outbounds")) input else JSONObject()
            .put("outbounds", JSONArray().put(input))
        val outbounds = root.optJSONArray("outbounds")
        require(outbounds != null && outbounds.length() > 0) { "Нет исходящих соединений в Xray JSON" }
        val tun = JSONObject().put("tag", "tun").put("port", 0).put("protocol", "tun")
            .put("settings", JSONObject().put("mtu", 1500))
            .put("sniffing", JSONObject().put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic")))
        root.put("inbounds", JSONArray().put(tun))
        if (!root.has("log")) root.put("log", JSONObject().put("loglevel", "warning"))
        return root.toString()
    }

    private fun vless(raw: String): JSONObject {
        val uri = URI(raw)
        val query = query(uri)
        val user = requireNotNull(uri.userInfo?.let(::decode)) { "В VLESS-ссылке отсутствует UUID" }
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", uri.host)
                .put("port", port(uri))
                .put("users", JSONArray().put(JSONObject()
                    .put("id", user)
                    .put("encryption", query["encryption"] ?: "none")
                    .put("flow", query["flow"] ?: ""),
                )),
            )))
        return addStream(outbound, query)
    }

    private fun vmess(raw: String): JSONObject {
        val encoded = raw.substringAfter("vmess://").substringBefore('#')
        val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        val query = mapOf(
            "type" to json.optString("net", "tcp"),
            "security" to json.optString("tls"),
            "sni" to json.optString("sni"),
            "host" to json.optString("host"),
            "path" to json.optString("path"),
            "serviceName" to json.optString("path"),
        )
        val outbound = JSONObject()
            .put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", json.getString("add"))
                .put("port", json.optInt("port", 443))
                .put("users", JSONArray().put(JSONObject()
                    .put("id", json.getString("id"))
                    .put("alterId", json.optInt("aid", 0))
                    .put("security", json.optString("scy", "auto")),
                )),
            )))
        return addStream(outbound, query)
    }

    private fun trojan(raw: String): JSONObject {
        val uri = URI(raw)
        val query = query(uri)
        val outbound = JSONObject()
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                .put("address", uri.host)
                .put("port", port(uri))
                .put("password", decode(uri.userInfo ?: "")),
            )))
        return addStream(outbound, query)
    }

    private fun shadowsocks(raw: String): JSONObject {
        val encoded = raw.substringAfter("ss://").substringBefore('#')
        val decoded = runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(encoded)
        val at = decoded.lastIndexOf('@')
        val credentials = if (at >= 0) decoded.substring(0, at) else ""
        val hostPort = if (at >= 0) decoded.substring(at + 1) else ""
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':').toIntOrNull() ?: 443
        return JSONObject()
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                .put("address", host).put("port", port).put("method", method).put("password", password),
            )))
    }

    private fun addStream(outbound: JSONObject, query: Map<String, String>): JSONObject {
        val network = query["type"] ?: query["network"] ?: "tcp"
        val stream = JSONObject().put("network", network)
        when (network.lowercase()) {
            "ws" -> stream.put("wsSettings", JSONObject()
                .put("path", query["path"] ?: "/")
                .put("headers", JSONObject().put("Host", query["host"] ?: query["sni"] ?: "")))
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", query["serviceName"] ?: query["path"] ?: ""))
            "http", "h2" -> stream.put("httpSettings", JSONObject()
                .put("path", query["path"] ?: "/")
                .put("host", JSONArray().put(query["host"] ?: "")))
            "splithttp", "xhttp" -> {
                stream.put("network", "xhttp")
                val settings = JSONObject().put("path", query["path"] ?: "/")
                    .put("host", query["host"] ?: "").put("mode", query["mode"] ?: "auto")
                query["extra"]?.let { runCatching { JSONObject(it) }.getOrNull()?.let { ext -> settings.put("extra", ext) } }
                stream.put("xhttpSettings", settings)
            }
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject()
                .put("path", query["path"] ?: "/")
                .put("host", query["host"] ?: ""))
        }
        when ((query["security"] ?: "").lowercase()) {
            "tls" -> stream.put("security", "tls").put("tlsSettings", tls(query))
            "reality" -> stream.put("security", "reality").put("realitySettings", reality(query))
            else -> stream.put("security", "none")
        }
        return outbound.put("streamSettings", stream)
    }

    private fun tls(query: Map<String, String>) = JSONObject()
        .put("serverName", query["sni"] ?: query["serverName"] ?: "")
        .put("allowInsecure", query["allowInsecure"] == "1" || query["allowInsecure"] == "true")
        .put("alpn", JSONArray().apply { query["alpn"]?.split(',')?.forEach { put(it) } })
        .put("fingerprint", query["fp"] ?: "chrome")

    private fun reality(query: Map<String, String>) = JSONObject()
        .put("serverName", query["sni"] ?: query["serverName"] ?: "")
        .put("publicKey", query["pbk"] ?: query["publicKey"] ?: "")
        .put("shortId", query["sid"] ?: query["shortId"] ?: "")
        .put("fingerprint", query["fp"] ?: query["fingerprint"] ?: "chrome")
        .put("spiderX", query["spx"] ?: query["spiderX"] ?: "/")

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty()
        .split('&').filter { it.isNotBlank() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }

    private fun port(uri: URI): Int = uri.port.takeIf { it > 0 } ?: 443
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
