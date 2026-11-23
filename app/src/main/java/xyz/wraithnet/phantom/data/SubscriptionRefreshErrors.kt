package xyz.wraithnet.phantom.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Locale

/** User-facing reason for a MANUAL refresh; does not change retries or network routing. */
internal object SubscriptionRefreshErrors {
    fun describe(failure: Throwable, throughVpn: Boolean): String {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase(Locale.ROOT)
        if (text.contains("vpn сменился") || text.contains("запущенная нода") ||
            text.contains("выбранная нода vpn")) {
            return "Активная VPN-нода отключилась или изменилась. Переподключите VPN."
        }
        val http = Regex("""\bhttp\s+(\d{3})\b""").find(text)?.groupValues?.getOrNull(1)
        if (http != null) return "Сервер подписки ответил HTTP $http"
        if (chain.any { it is InterruptedIOException } ||
            text.contains("timeout") || text.contains("timed out")) {
            return if (throughVpn)
                "Тайм-аут обновления (15 сек.) через активную VPN-ноду"
            else "Сервер подписки не ответил за 15 секунд"
        }
        if (chain.any { it is IllegalArgumentException } ||
            text.contains("не удалось распознать") || text.contains("пустую подписку") ||
            text.contains("пустой ответ сервера подписки")) {
            return "Сервер подписки вернул неверный формат ответа"
        }
        if (chain.any { it is UnknownHostException || it is ConnectException ||
                it is SocketException || it is IOException }) {
            return if (throughVpn)
                "Не удалось получить подписку через активную VPN-ноду. Проверьте её подключение."
            else "Сервер подписки недоступен. Проверьте сеть или адрес подписки."
        }
        return "Не удалось обновить подписку"
    }
}
