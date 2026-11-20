package xyz.wraithnet.phantom.data

/** Render request time consistently for every profile. */
object SubscriptionPingText {
    fun methodLabel(method: LatencyMethod?): String = when (method) {
        LatencyMethod.DIRECT_TCP -> "TCP"
        LatencyMethod.PROFILE_ROUTE -> "Маршрут"
        null -> ""
    }

    fun display(milliseconds: Long?, measuring: Boolean): String = when {
        milliseconds != null && milliseconds > 0L -> "$milliseconds мс"
        measuring -> "…"
        else -> "—"
    }
}
