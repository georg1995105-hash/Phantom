package xyz.wraithnet.phantom.data

import java.util.Locale

data class TrafficRate(val downloadBytesPerSecond: Long, val uploadBytesPerSecond: Long)

/** Decode delta counters returned by pinned AndroidLibXrayLite QueryAllOutboundTrafficStats. */
object TrafficRates {
    fun fromXrayReport(report: String, elapsedMs: Long): TrafficRate? {
        if (elapsedMs <= 0L) return null
        var upload = 0L
        var download = 0L
        for (entry in report.split(';')) {
            if (entry.isBlank()) continue
            val parts = entry.split(',')
            if (parts.size != 3) return null
            val value = parts[2].toLongOrNull() ?: return null
            if (value < 0L) return null
            if (parts[0] == "block") continue
            when (parts[1]) {
                "uplink" -> upload = (upload + value).takeIf { it >= upload } ?: Long.MAX_VALUE
                "downlink" -> download = (download + value).takeIf { it >= download } ?: Long.MAX_VALUE
                else -> return null
            }
        }
        return TrafficRate(
            (download.toDouble() * 1000.0 / elapsedMs).toLong(),
            (upload.toDouble() * 1000.0 / elapsedMs).toLong()
        )
    }

    /** Compact UI rates in decimal kilobits/second, like other Android VPN clients. */
    fun labelBits(bytesPerSecond: Long): String {
        if (bytesPerSecond < 0L) return "—"
        val kbitsPerSecond = bytesPerSecond.toDouble() * 8.0 / 1000.0
        if (kbitsPerSecond == 0.0) return "0 Кбит/с"
        val value: Double
        val unit: String
        if (kbitsPerSecond >= 1_000_000.0) {
            value = kbitsPerSecond / 1_000_000.0
            unit = "Гбит/с"
        } else if (kbitsPerSecond >= 1000.0) {
            value = kbitsPerSecond / 1000.0
            unit = "Мбит/с"
        } else {
            value = kbitsPerSecond
            unit = "Кбит/с"
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, unit)
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
