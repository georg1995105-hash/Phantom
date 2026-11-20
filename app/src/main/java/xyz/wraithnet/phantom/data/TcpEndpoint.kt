package xyz.wraithnet.phantom.data

import org.json.JSONObject

/** A single physical TCP entry that the config identifies without running its router. */
internal data class TcpEndpoint(val host: String, val port: Int) {
    companion object {
        fun forProfile(profile: ServerProfile): TcpEndpoint? {
            if (profile.scheme.equals("anytls", true) && !profile.raw.trimStart().startsWith("{")) {
                val uri = ShareLinkCodec.endpointUri(profile.raw)
                return of(uri.host, uri.port)
            }
            if (SingBoxBridgeConfig.requiresSingBox(profile)) return null
            val root = if (profile.raw.trimStart().startsWith("{")) JSONObject(profile.raw)
                else return fromOutbound(XrayConfigBuilder.shareOutbound(profile))
            val outbounds = root.optJSONArray("outbounds") ?: return fromOutbound(root)
            if ((root.optJSONObject("routing")?.optJSONArray("balancers")?.length() ?: 0) > 0 ||
                root.has("observatory") || root.has("burstObservatory")) return null
            val entries = (0 until outbounds.length()).map(outbounds::getJSONObject)
                .filter { it.optString("protocol") !in setOf("freedom", "blackhole", "dns") }
            // Xray routes unmatched traffic through its FIRST outbound. A lone TCP
            // entry later in the list is not necessarily the one applications use.
            // Other destinations in routing rules can also take precedence over it.
            // In ambiguous full JSON configurations, use the live core probe instead
            // of reporting a TCP connect to an unrelated proxy.
            if (entries.size != 1) return null
            val first = outbounds.optJSONObject(0) ?: return null
            val endpoint = fromOutbound(first) ?: return null
            val rules = root.optJSONObject("routing")?.optJSONArray("rules")
            if (rules != null && rules.length() > 0) {
                val expectedTag = first.optString("tag")
                if (expectedTag.isBlank()) return null
                for (index in 0 until rules.length()) {
                    val rule = rules.optJSONObject(index) ?: return null
                    if (rule.has("balancerTag") || rule.optString("outboundTag") != expectedTag) return null
                }
            }
            return endpoint
        }

        fun fromOutbound(outbound: JSONObject): TcpEndpoint? {
            val protocol = outbound.optString("protocol").lowercase()
            if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")) return null
            val stream = outbound.optJSONObject("streamSettings")
            val network = stream?.optString("method", stream.optString("network", "tcp"))?.lowercase() ?: "tcp"
            if (network !in setOf("tcp", "raw", "ws", "websocket", "grpc", "httpupgrade", "xhttp", "splithttp")) return null
            if (outbound.has("proxySettings") || outbound.has("sendThrough") ||
                stream?.optJSONObject("sockopt")?.let {
                    it.optString("dialerProxy").isNotBlank() || it.optString("interface").isNotBlank() ||
                        !it.optString("domainStrategy", "AsIs").equals("AsIs", true) || it.has("addressPortStrategy")
                } == true) return null
            if (network == "xhttp" || network == "splithttp") {
                // Xray 26.11.9 decideHTTPVersion uses H3 only with exactly one ALPN.
                val alpn = stream?.optJSONObject("tlsSettings")?.optJSONArray("alpn")
                if (stream?.optString("security").equals("tls", true) && alpn?.length() == 1 && alpn.optString(0) == "h3") return null
                val settings = stream?.optJSONObject("xhttpSettings") ?: stream?.optJSONObject("splithttpSettings")
                if ((settings?.optJSONObject("extra") ?: settings)?.has("downloadSettings") == true) return null
            }
            if (stream?.has("address") == true || stream?.has("port") == true) return null
            val settings = outbound.optJSONObject("settings") ?: return null
            val peer = if (settings.has("address")) settings else {
                val peers = settings.optJSONArray(if (protocol in setOf("vmess", "vless")) "vnext" else "servers")
                if (peers?.length() != 1) return null
                peers.optJSONObject(0) ?: return null
            }
            return of(peer.optString("address"), peer.optInt("port"))
        }

        /** The native counters belong to one successful request and its concurrent observers.
         * Only a single contributing TCP entry in a wholly identifiable graph proves a peer.
         * Otherwise keep the measured response through the complete profile.
         */
        fun observed(config: JSONObject, counters: String): TcpEndpoint? {
            val outbounds = config.getJSONArray("outbounds")
            val peers = mutableMapOf<String, TcpEndpoint>()
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.getJSONObject(i)
                if (outbound.optString("protocol") in setOf("freedom", "blackhole", "loopback", "dns")) continue
                val tag = outbound.optString("tag")
                if (tag.isBlank() || tag.any { it in ",;>" } || tag in peers) return null
                peers[tag] = fromOutbound(outbound) ?: return null
            }
            val active = mutableSetOf<String>()
            for (counter in counters.split(';')) {
                if (counter.isEmpty()) continue
                val fields = counter.split(',')
                if (fields.size != 3) return null
                if (fields[0] in peers && fields[1] == "downlink" && (fields[2].toLongOrNull() ?: 0L) > 0)
                    active.add(fields[0])
            }
            return active.singleOrNull()?.let(peers::get)
        }

        private fun of(host: String?, port: Int): TcpEndpoint? =
            if (host.isNullOrBlank() || port !in 1..65535) null
            else TcpEndpoint(host.removeSurrounding("[", "]"), port)
    }
}
