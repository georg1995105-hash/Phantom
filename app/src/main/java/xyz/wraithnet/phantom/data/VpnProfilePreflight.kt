package xyz.wraithnet.phantom.data

/**
 * Rejects unsupported/malformed replacement profiles before stopping the
 * currently working native cores. This check is pure JVM/JSON: no network,
 * VPN interface, Android service, or Go runtime is touched.
 */
internal object VpnProfilePreflight {
    data class Prepared(
        val scheme: String,
        val usesSingBox: Boolean,
        // Already validated, immutable source for non-sing-box profiles.
        val xrayConfig: String?,
    )

    private const val VALIDATION_PORT = 10800

    fun prepare(
        raw: String,
        dns: VpnDnsSettings,
        fragmentation: FragmentationOptions,
    ): Prepared {
        val value = raw.trim()
        require(value.isNotEmpty()) { "Профиль сервера пуст" }
        check(dns.validate() == null) { dns.validate() ?: "Неверные DNS" }
        val scheme = if (value.startsWith("{")) "xray"
            else value.substringBefore(':').lowercase()
        val profile = ServerProfile("preflight", "preflight", scheme, raw = raw)
        val usesSingBox = SingBoxBridgeConfig.requiresSingBox(profile)
        val base = if (usesSingBox) {
            // Check credentials, UDP/TLS options and scheme without starting
            // a native worker or resolving a remote server address.
            SingBoxBridgeConfig.fromShareLink(raw, VALIDATION_PORT)
            XrayConfigBuilder.buildSocksBridge(VALIDATION_PORT)
        } else {
            XrayConfigBuilder.build(profile)
        }
        val adapted = dns.applyToXray(fragmentation.apply(base))
        val withDns = if (scheme == "xray" && dns.mode == VpnDnsSettings.Mode.DEFAULT)
            XrayConfigBuilder.withProviderTunDns(adapted) ?: adapted else adapted
        // Catch malformed inbound/routing adapters before TUN teardown as well.
        XrayConfigBuilder.withConnectivityCheck(withDns, 18123, "preflight")
        return Prepared(scheme, usesSingBox, if (usesSingBox) null else base)
    }
}
