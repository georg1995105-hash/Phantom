package xyz.wraithnet.phantom.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Builds an Xray TUN configuration from one Xray-compatible share link. */
object XrayConfigBuilder {
    fun build(profile: ServerProfile): String {
        val source = profile.raw.trim()
        if (source.startsWith("{")) return buildJson(source)
        require(profile.scheme.lowercase() in setOf("vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2")) {
            "${profile.scheme.uppercase()} работает через sing-box и пока не подключён"
        }

        val outbound = when (profile.scheme.lowercase()) {
            "vless" -> vless(profile.raw)
            "vmess" -> vmess(profile.raw)
            "trojan" -> trojan(profile.raw)
            "ss" -> shadowsocks(profile.raw)
            "hysteria", "hysteria2", "hy2" -> hysteria2(profile.raw)
            else -> error("Неподдерживаемый Xray-протокол")
        }.put("tag", "proxy")

        return enableTrafficStats(JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(
                JSONObject()
                    .put("tag", "tun")
                    .put("port", 0)
                    .put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "tun0").put("mtu", 1400).put("userLevel", 8))
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
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))))
            .toString()
    }

    /** Preserve complete Xray JSON from any provider, replacing local inbounds with TUN. */
    private fun buildJson(raw: String): String {
        val input = JSONObject(raw)
        val root = if (input.has("outbounds")) input else JSONObject()
            .put("outbounds", JSONArray().put(input))
        val outbounds = root.optJSONArray("outbounds")
        require(outbounds != null && outbounds.length() > 0) { "Нет исходящих соединений в Xray JSON" }
        val tun = JSONObject().put("tag", "tun").put("port", 0).put("protocol", "tun")
            .put("settings", JSONObject().put("name", "tun0").put("mtu", 1400))
            .put("sniffing", JSONObject().put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic")))
        // Preserve inbound-based routing rules when migrating local SOCKS/HTTP
        // inbounds to Android's single TUN inbound.
        val oldInbounds = root.optJSONArray("inbounds")
        val oldTags = (0 until (oldInbounds?.length() ?: 0)).mapNotNull { index ->
            oldInbounds?.optJSONObject(index)?.optString("tag")?.takeIf { it.isNotBlank() }
        }.toSet()
        root.optJSONObject("routing")?.optJSONArray("rules")?.let { rules ->
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                val tags = rule.optJSONArray("inboundTag") ?: continue
                if ((0 until tags.length()).any { tags.optString(it) in oldTags }) {
                    rule.put("inboundTag", JSONArray().put("tun"))
                }
            }
        }
        root.put("inbounds", JSONArray().put(tun))
        // Fail clearly on profiles created for removed transport networks.
        // Do not rewrite third-party profiles: that could alter their security semantics.
        for (i in 0 until outbounds.length()) {
            val oldNetwork = outbounds.optJSONObject(i)?.optJSONObject("streamSettings")
                ?.optString("network")?.lowercase() ?: continue
            require(oldNetwork !in setOf("quic", "h2", "h3", "http")) {
                "Этот Xray JSON использует удалённый транспорт $oldNetwork. " +
                    "Нужна серверная конфигурация XHTTP/H3 или Hysteria2."
            }
        }
        // Keep UDP QUIC sessions alive on idle mobile networks.
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("protocol") != "hysteria") continue
            val stream = outbound.optJSONObject("streamSettings") ?: continue
            val mask = stream.optJSONObject("finalmask") ?: JSONObject()
            val quic = mask.optJSONObject("quicParams") ?: JSONObject()
            if (!quic.has("keepAlivePeriod")) quic.put("keepAlivePeriod", 15)
            stream.put("finalmask", mask.put("quicParams", quic))
        }
        if (!root.has("log")) root.put("log", JSONObject().put("loglevel", "warning"))
        return enableTrafficStats(root).toString()
    }

    /** Preserve provider routing and policies while enabling outbound byte counters. */
    private fun enableTrafficStats(root: JSONObject): JSONObject {
        if (!root.has("stats")) root.put("stats", JSONObject())
        val policy = root.optJSONObject("policy") ?: JSONObject()
        val system = policy.optJSONObject("system") ?: JSONObject()
        system.put("statsOutboundUplink", true)
        system.put("statsOutboundDownlink", true)
        root.put("policy", policy.put("system", system))
        return root
    }

    /** Native Xray 26.x Hysteria2 transport with QUIC keepalive and Salamander. */
    private fun hysteria2(raw: String): JSONObject {
        val uri = URI(raw)
        val q = query(uri)
        val host = requireNotNull(uri.host) { "У Hysteria2 нет адреса сервера" }
        val auth = decode(uri.rawUserInfo ?: "")
        require(auth.isNotBlank()) { "В ссылке Hysteria2 нет пароля" }
        val stream = JSONObject()
            .put("network", "hysteria")
            .put("security", "tls")
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", auth)
                .put("udpIdleTimeout", 60))
            .put("tlsSettings", JSONObject()
                .put("serverName", q["sni"] ?: q["peer"] ?: host)
                .put("alpn", JSONArray().apply {
                    (q["alpn"] ?: "h3").split(",").forEach { put(it) }
                })
                .put("fingerprint", q["fp"] ?: "chrome")
                .put("allowInsecure", q["insecure"] == "1" || q["insecure"] == "true"))
        val finalmask = JSONObject().put("quicParams", JSONObject().put("keepAlivePeriod", 15))
        if (q["obfs"]?.lowercase() == "salamander") {
            val password = q["obfs-password"].orEmpty()
            require(password.isNotBlank()) { "У Salamander отсутствует пароль" }
            finalmask.put("udp", JSONArray().put(JSONObject().put("type", "salamander")
                .put("settings", JSONObject().put("password", password))))
        } else if (!q["obfs"].isNullOrBlank()) {
            error("Эта маскировка Hysteria2 пока не поддерживается")
        }
        q["mport"]?.takeIf { it.isNotBlank() }?.let {
            finalmask.getJSONObject("quicParams").put("udpHop",
                JSONObject().put("ports", it).put("interval",
                    q["mportHopInt"]?.toIntOrNull()?.coerceAtLeast(5) ?: 30))
        }
        stream.put("finalmask", finalmask)
        return JSONObject()
            .put("protocol", "hysteria")
            .put("settings", JSONObject().put("version", 2).put("address", host).put("port", port(uri)))
            .put("streamSettings", stream)
    }

    private fun vless(raw: String): JSONObject {
        val uri = URI(raw)
        val query = query(uri)
        val user = requireNotNull(uri.rawUserInfo?.let(::decode)) { "В VLESS-ссылке отсутствует UUID" }
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
        val json = JSONObject(ShareLinkCodec.base64Text(encoded))
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
                .put("password", decode(uri.rawUserInfo ?: "")),
            )))
        return addStream(outbound, query)
    }

    private fun shadowsocks(raw: String): JSONObject {
        val credentials = ShareLinkCodec.shadowsocks(raw)
        return JSONObject()
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                .put("address", credentials.host)
                .put("port", credentials.port)
                .put("method", credentials.method)
                .put("password", credentials.password))))
    }

    private fun addStream(outbound: JSONObject, query: Map<String, String>): JSONObject {
        val requested = (query["type"] ?: query["network"] ?: "tcp").lowercase()
        // Standalone QUIC/H2/H3 networks were removed from Xray. For QUIC-backed
        // sessions use Hysteria2 or XHTTP with TLS ALPN h3 instead of generating
        // configs that cannot possibly start.
        require(requested !in setOf("quic", "h2", "h3", "http")) {
            "Старый транспорт $requested удалён из Xray. Используйте XHTTP с ALPN h3 или Hysteria2."
        }
        val network = when (requested) {
            "splithttp" -> "xhttp"
            "kcp" -> "mkcp"
            "websocket" -> "ws"
            else -> requested
        }
        if (network == "xhttp" && query["alpn"]?.split(',')?.any {
                it.trim().equals("h3", ignoreCase = true)
            } == true) {
            require((query["security"] ?: "none").equals("tls", ignoreCase = true)) {
                "XHTTP через HTTP/3 требует TLS и ALPN h3"
            }
        }
        val stream = JSONObject().put("network", network)
        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject()
                .put("path", query["path"] ?: "/")
                .put("headers", JSONObject().put("Host", query["host"] ?: query["sni"] ?: "")))
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", query["serviceName"] ?: query["path"] ?: ""))
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
            "mkcp" -> stream.put("kcpSettings", JSONObject().apply {
                query["seed"]?.takeIf { it.isNotBlank() }?.let { put("seed", it) }
            })
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
    private fun decode(value: String): String = ShareLinkCodec.percentDecode(value)
}
