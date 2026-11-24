package xyz.wraithnet.phantom.data

import org.json.JSONObject

/**
 * A startup check must prove a real HTTPS roundtrip through the selected VPN outbound.
 *
 * Prefer two independent networks: a 204 endpoint can be filtered or rewritten by
 * a provider even though the actual tunnel is healthy. A successful TLS-verified
 * 200 response from Cloudflare's trace endpoint is equally valid evidence.
 *
 * Adaptive Xray balancers can need one observation interval before selecting a
 * healthy outbound. Give only these profiles a strictly bounded retry window.
 */
internal object ConnectivityProbePlan {
    data class Step(
        val url: String,
        val expectedStatus: Int,
        val timeoutMs: Int,
        val pauseBeforeMs: Long = 0L,
    )

    private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val GSTATIC = "https://www.gstatic.com/generate_204"
    private const val CLOUDFLARE = "https://cp.cloudflare.com/generate_204"
    private const val EXAMPLE = "https://example.com/"

    fun forConfig(xrayConfig: String): List<Step> {
        val root = JSONObject(xrayConfig)
        val hasObserver = root.optJSONObject("burstObservatory") != null ||
            root.optJSONObject("observatory") != null
        val hasBalancer = (root.optJSONObject("routing")?.optJSONArray("balancers")
            ?.length() ?: 0) > 0

        val primary = listOf(
            Step(TRACE, 200, 4_000),
            Step(GSTATIC, 204, 3_000),
            Step(CLOUDFLARE, 204, 3_000),
            Step(EXAMPLE, 200, 3_000),
        )
        if (!hasObserver && !hasBalancer) return primary

        // The first observer measurement is often asynchronous. Never allow an
        // indefinite retry: if the extra probes fail, the VPN remains disconnected.
        return primary + listOf(
            Step(TRACE, 200, 6_000, pauseBeforeMs = 700),
            Step(GSTATIC, 204, 6_000, pauseBeforeMs = 400),
        )
    }
}
