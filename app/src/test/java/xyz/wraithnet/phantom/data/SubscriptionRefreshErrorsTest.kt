package xyz.wraithnet.phantom.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRefreshErrorsTest {
    @Test fun vpnChangedDoesNotLookLikeBadSubscriptionServer() {
        assertEquals("Активная VPN-нода отключилась или изменилась. Переподключите VPN.",
            SubscriptionRefreshErrors.describe(IllegalStateException("VPN сменился во время обновления"), true))
    }
    @Test fun timedOutThroughVpnHasExplicitBound() {
        assertEquals("Тайм-аут обновления (15 сек.) через активную VPN-ноду",
            SubscriptionRefreshErrors.describe(IOException("request failed", SocketTimeoutException()), true))
    }
    @Test fun subscriptionHttpErrorDoesNotClaimVpnFailure() {
        assertEquals("Сервер подписки ответил HTTP 503",
            SubscriptionRefreshErrors.describe(IOException("Сервер ответил HTTP 503"), true))
    }
    @Test fun invalidResponseIsNotNetworkError() {
        assertEquals("Сервер подписки вернул неверный формат ответа",
            SubscriptionRefreshErrors.describe(IllegalArgumentException("Некорректный формат ответа"), false))
    }
    @Test fun unreachableSubscriptionServerDoesNotCertifyNodeAsBroken() {
        assertEquals("Не удалось получить подписку через активную VPN-ноду. Проверьте её подключение.",
            SubscriptionRefreshErrors.describe(UnknownHostException("unresolved"), true))
    }
}
