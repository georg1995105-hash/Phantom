package xyz.wraithnet.phantom.data

import java.util.Locale

/** Device-wide network counters, NOT Xray/VPN tunnel statistics. */
data class TrafficSnapshot(val elapsedMs: Long, val rxBytes: Long, val txBytes: Long)

data class TrafficRate(val downloadBytesPerSecond: Long, val uploadBytesPerSecond: Long)

object TrafficRates {
    fun between(before: TrafficSnapshot, after: TrafficSnapshot): TrafficRate? {
        val elapsed = after.elapsedMs - before.elapsedMs
        val rx = after.rxBytes - before.rxBytes
        val tx = after.txBytes - before.txBytes
        if (elapsed <= 0 || before.rxBytes < 0 || before.txBytes < 0 ||
            after.rxBytes < 0 || after.txBytes < 0 || rx < 0 || tx < 0) return null
        return TrafficRate(
            (rx.toDouble() * 1000.0 / elapsed).toLong(),
            (tx.toDouble() * 1000.0 / elapsed).toLong()
        )
    }

    fun label(bytesPerSecond: Long): String {
        if (bytesPerSecond < 0L) return "—"
        val units = listOf("Б/с", "КБ/с", "МБ/с", "ГБ/с")
        var value = bytesPerSecond.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "${bytesPerSecond} Б/с"
        else String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }
}
