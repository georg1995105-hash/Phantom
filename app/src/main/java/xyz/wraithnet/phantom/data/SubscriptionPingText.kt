package xyz.wraithnet.phantom.data

/** Only a measured RTT is shown to users; probe transport is an internal detail. */
object SubscriptionPingText {
    fun display(result: LatencyProbe.PingResult?, measuring: Boolean): String = when {
        result != null && result.milliseconds > 0L -> "${result.milliseconds} мс"
        measuring -> "…"
        else -> "—"
    }
}
