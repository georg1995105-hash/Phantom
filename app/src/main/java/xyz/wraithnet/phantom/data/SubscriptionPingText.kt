package xyz.wraithnet.phantom.data

/** Render request time consistently for every profile. */
object SubscriptionPingText {
    fun display(milliseconds: Long?, measuring: Boolean): String = when {
        milliseconds != null && milliseconds > 0L -> "$milliseconds мс"
        measuring -> "…"
        else -> "—"
    }
}
