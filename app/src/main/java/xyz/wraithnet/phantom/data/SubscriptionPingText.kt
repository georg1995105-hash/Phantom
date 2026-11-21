package xyz.wraithnet.phantom.data

/** Render request time consistently for every profile. */
object SubscriptionPingText {
    fun display(milliseconds: Long?, measuring: Boolean): String = when {
        measuring -> "…"
        milliseconds != null && milliseconds > 0L -> "$milliseconds мс"
        else -> "—"
    }
}
