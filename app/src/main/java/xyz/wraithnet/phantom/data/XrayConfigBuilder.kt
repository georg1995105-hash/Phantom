package xyz.wraithnet.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Builds an Xray TUN configuration from one Xray-compatible share link. */
object XrayConfigBuilder {
    internal fun forLatency(config: String, port: Int, password: String): String {
        val root = JSONObject(config)
        // Keep the same inbound tag and routing, including provider balancers.
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        configureHttpProbe(inbound, port, password)
        // A direct route must not produce a proxy latency. Still retain any
        // freedom outbound used to dial a real proxy's transport.
        val outbounds = root.getJSONArray("outbounds")
        val original = (0 until outbounds.length()).map(outbounds::getJSONObject)
        val tags = original.map { it.optString("tag") }.filter(String::isNotEmpty)
        val helpers = mutableListOf<String>()
        // Native DNS dispatches with dns.tag (or a per-server tag). Its traffic
        // must still follow the provider rules even when the probe forbids direct egress.
        val dnsTags = mutableSetOf<String>()
        root.optJSONObject("dns")?.let { dns ->
            val default = dns.optString("tag").ifBlank { "phantom-dns-$password" }
            dns.put("tag", default)
            dnsTags.add(default)
            val servers = dns.optJSONArray("servers") ?: JSONArray()
            for (i in 0 until servers.length()) {
                servers.optJSONObject(i)?.optString("tag")?.takeIf(String::isNotBlank)?.let(dnsTags::add)
            }
        }
        val dnsDetours = mutableMapOf<String, String>()
        var defaultDnsDetour: String? = null
        val sockets = original.flatMap { outbound ->
            val stream = outbound.optJSONObject("streamSettings")
            val xhttp = stream?.let { it.optJSONObject("xhttpSettings") ?: it.optJSONObject("splithttpSettings") }
            val download = (xhttp?.optJSONObject("extra") ?: xhttp)?.optJSONObject("downloadSettings")
            listOfNotNull(stream?.optJSONObject("sockopt"), download?.optJSONObject("sockopt"))
        }
        for ((index, outbound) in original.withIndex()) {
            if (outbound.optString("protocol") != "freedom") continue
            val tag = outbound.optString("tag")
            val detours = sockets.filter { tag.isNotEmpty() && it.optString("dialerProxy") == tag }
            val chains = original.mapNotNull { it.optJSONObject("proxySettings") }
                .filter { tag.isNotEmpty() && it.optString("tag") == tag }
            if (detours.isNotEmpty() || chains.isNotEmpty() || dnsTags.isNotEmpty()) {
                // Pick a prefix no original tag can select, even after expanding a catch-all.
                val prefix = ('\uE000'..'\uF8FF').first { candidate -> tags.none { it.first() == candidate } }
                val helperTag = "${prefix}phantom-probe-dial-$index"
                helpers.add(helperTag)
                detours.forEach { it.put("dialerProxy", helperTag) }
                chains.forEach { it.put("tag", helperTag) }
                if (tag.isNotBlank()) dnsDetours[tag] = helperTag
                if (index == 0) defaultDnsDetour = helperTag
                outbounds.put(JSONObject(outbound.toString()).put("tag", helperTag))
            }
            outbounds.put(index, JSONObject().put("tag", tag).put("protocol", "blackhole"))
        }
        if (dnsTags.isNotEmpty() && (dnsDetours.isNotEmpty() || defaultDnsDetour != null)) {
            val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
            val rules = routing.optJSONArray("rules") ?: JSONArray()
            val updated = JSONArray()
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                val helper = dnsDetours[rule.optString("outboundTag")]
                if (helper != null && rule.has("outboundTag")) {
                    val inbounds = rule.optJSONArray("inboundTag")
                    val relevant = dnsTags.filter { tag ->
                        inbounds == null || (0 until inbounds.length()).any { inbounds.optString(it) == tag }
                    }
                    if (relevant.isNotEmpty()) updated.put(JSONObject(rule.toString())
                        .put("inboundTag", JSONArray(relevant)).put("outboundTag", helper))
                }
                updated.put(rule)
            }
            defaultDnsDetour?.let { helper ->
                updated.put(JSONObject().put("type", "field").put("inboundTag", JSONArray(dnsTags))
                    .put("outboundTag", helper))
            }
            routing.put("rules", updated)
        }
        // Xray's selectors are prefixes. Keep internal dialers out of catch-all balancers and observers.
        val balancers = root.optJSONObject("routing")?.optJSONArray("balancers") ?: JSONArray()
        for (index in 0 until balancers.length()) {
            balancers.optJSONObject(index)?.excludeInternalOutbounds("selector", helpers, tags)
        }
        root.optJSONObject("observatory")?.excludeInternalOutbounds("subjectSelector", helpers, tags)
        root.optJSONObject("burstObservatory")?.excludeInternalOutbounds("subjectSelector", helpers, tags)
        root.remove("api")
        root.remove("metrics")
        root.put("log", JSONObject().put("loglevel", "none").put("access", "none"))
        return root.toString()
    }

    internal const val TUN_RESOLVER = "10.111.0.1"

    /** Connect Android's system resolver to the DNS policy supplied in a full profile. */
    internal fun withProviderTunDns(config: String): String? {
        val root = JSONObject(config)
        val dns = root.optJSONObject("dns") ?: return null
        if ((dns.optJSONArray("servers")?.length() ?: 0) == 0 &&
            (dns.optJSONObject("hosts")?.length() ?: 0) == 0) return null
        val outbounds = root.getJSONArray("outbounds")
        val existing = (0 until outbounds.length()).map(outbounds::getJSONObject)
        val existingDns = existing.singleOrNull { it.optString("protocol") == "dns" }
            ?.optString("tag")?.takeIf(String::isNotBlank)
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val resolver = existingDns ?: run {
            val tags = existing.map { it.optString("tag") }.filter(String::isNotEmpty)
            val prefix = ('\uE000'..'\uF8FF').first { prefix -> tags.none { it.first() == prefix } }
            val tag = "${prefix}phantom-tun-dns"
            outbounds.put(JSONObject().put("protocol", "dns").put("tag", tag))
            val balancers = routing.optJSONArray("balancers") ?: JSONArray()
            for (i in 0 until balancers.length())
                balancers.getJSONObject(i).excludeInternalOutbounds("selector", listOf(tag), tags)
            for (key in listOf("observatory", "burstObservatory"))
                root.optJSONObject(key)?.excludeInternalOutbounds("subjectSelector", listOf(tag), tags)
            tag
        }
        // Only the Android resolver at our own TUN gateway gets this adapter.
        // Its upstream queries retain the provider's DNS tags, order, routing and fallbacks.
        val rules = JSONArray().put(JSONObject().put("type", "field").put("ip", JSONArray().put(TUN_RESOLVER))
            .put("port", 53).put("inboundTag", JSONArray().put(root.getJSONArray("inbounds").getJSONObject(0).getString("tag")))
            .put("outboundTag", resolver))
        routing.optJSONArray("rules")?.let { original ->
            for (i in 0 until original.length()) rules.put(original.get(i))
        }
        routing.put("rules", rules)
        return root.toString()
    }

    private fun configureHttpProbe(inbound: JSONObject, port: Int, password: String) {
        inbound.put("protocol", "http").put("listen", "127.0.0.1").put("port", port)
            .put("settings", JSONObject().put("accounts", JSONArray().put(
                JSONObject().put("user", "phantom").put("pass", password)
            )))
    }

    /** Native CoreController.measureDelay has no inbound tag. Check the routing used by Android apps. */
    internal fun withConnectivityCheck(config: String, port: Int, password: String): String {
        val root = JSONObject(config)
        val inbounds = root.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)
        val probeTag = "phantom-check-$password"
        val probe = JSONObject(tun.toString()).put("tag", probeTag)
        configureHttpProbe(probe, port, password)
        inbounds.put(probe)
        val rules = root.optJSONObject("routing")?.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rules.length()) {
            val tags = rules.getJSONObject(i).optJSONArray("inboundTag") ?: continue
            if ((0 until tags.length()).any { tags.optString(it) == tun.getString("tag") }) tags.put(probeTag)
        }
        return root.toString()
    }

    private fun JSONObject.excludeInternalOutbounds(key: String, helpers: List<String>, original: List<String>) {
        if (helpers.isEmpty()) return
        val array = optJSONArray(key) ?: return
        val selectors = (0 until array.length()).map(array::getString)
        if (helpers.any { helper -> selectors.any { helper.startsWith(it) } }) {
            put(key, JSONArray(original.filter { tag -> selectors.any { tag.startsWith(it) } }.distinct()))
        }
    }

    fun build(profile: ServerProfile): String {
        val source = profile.raw.trim()
        if (source.startsWith("{")) return buildJson(source)
        val outbound = shareOutbound(profile).put("tag", "proxy")

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

    internal fun shareOutbound(profile: ServerProfile): JSONObject {
        require(profile.scheme.lowercase() in setOf("vless", "vmess", "trojan", "ss", "hysteria", "hysteria2", "hy2")) {
            "${profile.scheme.uppercase()} работает через sing-box и пока не подключён"
        }

        return when (profile.scheme.lowercase()) {
            "vless" -> vless(profile.raw)
            "vmess" -> vmess(profile.raw)
            "trojan" -> trojan(profile.raw)
            "ss" -> shadowsocks(profile.raw)
            "hysteria", "hysteria2", "hy2" -> hysteria2(profile.raw)
            else -> error("Неподдерживаемый Xray-протокол")
        }
    }

    /** Xray keeps the stable Android TUN; SOCKS5 forwards TCP and UDP into sing-box.
     * This is a real transport bridge, not a substitute for an unavailable protocol.
     */
    fun buildSocksBridge(localPort: Int): String {
        require(localPort in 1024..65535) { "Некорректный локальный SOCKS-порт" }
        return enableTrafficStats(JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "tun")
                .put("port", 0)
                .put("protocol", "tun")
                .put("settings", JSONObject()
                    .put("name", "tun0").put("mtu", 1400).put("userLevel", 8))
                .put("sniffing", JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic")))))
            .put("outbounds", JSONArray()
                .put(JSONObject()
                    .put("tag", "proxy")
                    .put("protocol", "socks")
                    .put("settings", JSONObject()
                        .put("address", "127.0.0.1").put("port", localPort)))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("routing", JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", JSONArray().put(JSONObject()
                    .put("type", "field").put("network", "tcp,udp")
                    .put("outboundTag", "proxy"))))
            .put("dns", JSONObject().put("servers",
                JSONArray().put("1.1.1.1").put("8.8.8.8"))))
            .toString()
    }

    /** Preserve complete Xray JSON from any provider, replacing local inbounds with TUN. */
    internal fun buildJson(raw: String): String {
        val input = JSONObject(raw)
        val root = if (input.has("outbounds")) input else JSONObject()
            .put("outbounds", JSONArray().put(input))
        val outbounds = root.optJSONArray("outbounds")
        require(outbounds != null && outbounds.length() > 0) { "Нет исходящих соединений в Xray JSON" }
        // Android exposes one TUN. Never merge distinct inbound routing policies.
        // An explicit provider TUN is authoritative; otherwise the local proxy
        // inbounds must agree on every inboundTag predicate before adaptation.
        val oldInbounds = root.optJSONArray("inbounds")
        val clientProtocols = listOf("tun", "socks", "mixed", "http")
        val clients = (0 until (oldInbounds?.length() ?: 0)).mapNotNull { oldInbounds?.optJSONObject(it) }
            .filter { it.optString("protocol").lowercase() in clientProtocols }
        val explicitTuns = clients.filter { it.optString("protocol").equals("tun", ignoreCase = true) }
        require(explicitTuns.size <= 1) { "Xray JSON содержит несколько TUN-входов: маршрут неоднозначен" }
        val rules = root.optJSONObject("routing")?.optJSONArray("rules") ?: JSONArray()
        val selected = explicitTuns.singleOrNull()
            ?: clients.minByOrNull { clientProtocols.indexOf(it.optString("protocol").lowercase()) }
        if (explicitTuns.isEmpty() && selected != null) {
            for (i in 0 until rules.length()) {
                val tags = rules.optJSONObject(i)?.optJSONArray("inboundTag") ?: continue
                fun matches(inbound: JSONObject): Boolean =
                    (0 until tags.length()).any { tags.optString(it) == inbound.optString("tag") }
                require(clients.all { matches(it) == matches(selected) }) {
                    "Xray JSON задаёт разные маршруты для локальных входов. Нужен явный TUN-вход"
                }
            }
        }
        val sourceTag = selected?.optString("tag").orEmpty()
        val dns = root.optJSONObject("dns")
        val dnsTags = mutableSetOf<String>()
        dns?.optString("tag")?.takeIf(String::isNotBlank)?.let(dnsTags::add)
        val dnsServers = dns?.optJSONArray("servers") ?: JSONArray()
        for (i in 0 until dnsServers.length()) {
            dnsServers.optJSONObject(i)?.optString("tag")?.takeIf(String::isNotBlank)?.let(dnsTags::add)
        }
        val reservedTags = mutableSetOf<String>().apply {
            addAll(dnsTags)
            for (i in 0 until (oldInbounds?.length() ?: 0)) {
                oldInbounds?.optJSONObject(i)?.optString("tag")?.takeIf(String::isNotBlank)?.let(::add)
            }
            for (i in 0 until rules.length()) {
                val tags = rules.optJSONObject(i)?.optJSONArray("inboundTag") ?: continue
                for (j in 0 until tags.length()) add(tags.optString(j))
            }
        }
        require(sourceTag.isBlank() || sourceTag !in dnsTags) {
            "Xray JSON использует общий тег для DNS и клиентского входа"
        }
        val tunTag = sourceTag.ifBlank {
            if ((oldInbounds?.length() ?: 0) == 0 && "tun" !in dnsTags) "tun"
            else generateSequence(0) { it + 1 }.map { if (it == 0) "tun" else "phantom-tun-$it" }
                .first { it !in reservedTags }
        }
        // Preserve an explicitly configured TUN's options, including userLevel.
        // Only interface identity and MTU are Android-owned.
        val sniffing = selected?.optJSONObject("sniffing")
            ?: JSONObject().put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
        val tunSettings = if (explicitTuns.isNotEmpty())
            explicitTuns.single().optJSONObject("settings") ?: JSONObject()
            else JSONObject()
        val tun = JSONObject().put("tag", tunTag).put("port", 0).put("protocol", "tun")
            .put("settings", tunSettings.put("name", "tun0").put("mtu", 1400))
            .put("sniffing", sniffing)
        // Keep the provider routing array byte-for-byte semantically unchanged.
        // Removed local listeners are not opened on Android; their tags must
        // never be aliased onto the chosen TUN.
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
        // Full provider JSON owns its transport tuning. Share links still get
        // Phantom's defaults, but imported outbounds must not be rewritten.
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
        val uri = ShareLinkCodec.endpointUri(raw)
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
                .apply { applyCertificateOptions(q) })
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
        val uri = ShareLinkCodec.endpointUri(raw)
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
        val json = ShareLinkCodec.vmess(raw)
        val query = mapOf(
            "type" to json.optString("net", "tcp"),
            "security" to json.optString("tls"),
            "sni" to json.optString("sni"),
            "host" to json.optString("host"),
            "path" to json.optString("path"),
            "serviceName" to json.optString("path"),
            "headerType" to json.optString("type"),
            "mode" to json.optString("mode").ifBlank {
                json.optString("type").takeIf { json.optString("net") == "grpc" }.orEmpty()
            },
            "authority" to json.optString("authority"),
            "extra" to json.optString("extra"),
            "alpn" to json.optString("alpn"),
            "fp" to json.optString("fp"),
        ).filterValues(String::isNotBlank)
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
        val uri = ShareLinkCodec.endpointUri(raw)
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
            "raw", "tcp" -> if (query["headerType"].equals("http", ignoreCase = true)) {
                val request = JSONObject()
                query["path"]?.takeIf(String::isNotBlank)?.let { request.put("path", JSONArray(it.split(','))) }
                query["host"]?.takeIf(String::isNotBlank)?.let {
                    request.put("headers", JSONObject().put("Host", JSONArray(it.split(','))))
                }
                stream.put("rawSettings", JSONObject().put("header",
                    JSONObject().put("type", "http").put("request", request)))
            }
            "ws" -> stream.put("wsSettings", JSONObject().put("path", query["path"] ?: "/").apply {
                query["host"]?.takeIf(String::isNotBlank)?.let { put("host", it) }
            })
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", query["serviceName"] ?: query["path"] ?: "")
                .put("multiMode", query["mode"].equals("multi", ignoreCase = true))
                .apply { query["authority"]?.takeIf(String::isNotBlank)?.let { put("authority", it) } })
            "splithttp", "xhttp" -> {
                stream.put("network", "xhttp")
                val settings = JSONObject().put("path", query["path"] ?: "/")
                    .put("host", query["host"] ?: "").put("mode", query["mode"] ?: "auto")
                query["extra"]?.takeIf(String::isNotBlank)?.let {
                    val extra = runCatching { JSONObject(it) }.getOrElse {
                        throw IllegalArgumentException("Параметр extra в XHTTP должен содержать JSON-объект")
                    }
                    settings.put("extra", extra)
                }
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
        .apply { applyCertificateOptions(query) }
        .put("alpn", JSONArray(query["alpn"].orEmpty().split(',').map(String::trim).filter(String::isNotBlank)))
        .put("fingerprint", query["fp"] ?: "chrome")

    private fun JSONObject.applyCertificateOptions(query: Map<String, String>) {
        val pin = query["pcs"] ?: query["pinSHA256"] ?: query["pinnedPeerCertSha256"]
        pin?.takeIf { it.isNotBlank() }?.let { put("pinnedPeerCertSha256", it) }
        query["vcn"]?.takeIf { it.isNotBlank() }?.let { put("verifyPeerCertByName", it) }
        val insecure = listOf("allowInsecure", "insecure", "allow_insecure").any {
            query[it] == "1" || query[it].equals("true", ignoreCase = true)
        }
        require(!insecure || !pin.isNullOrBlank()) {
            "Ядру нужен проверяемый сертификат. Запросите ссылку с отпечатком SHA-256 или доверенным сертификатом."
        }
    }

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
