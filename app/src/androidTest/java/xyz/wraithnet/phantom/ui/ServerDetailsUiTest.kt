package xyz.wraithnet.phantom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.wraithnet.phantom.data.ServerProfile
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.ui.home.ServersPage

@RunWith(AndroidJUnit4::class)
class ServerDetailsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerLinksAndDetailsStayWithTheirOwnerOnANarrowScreen() {
        val phantom = Subscription("one", "Phantom VPN", "https://first.example/sub",
            listOf(ServerProfile("same", "Первый", "vless", raw = "first")),
            webPageUrl = "https://first.example/account", supportUrl = "https://t.me/first",
            announcement = "Сообщение первого", totalBytes = 0, expiresAt = 1893456000)
        val other = phantom.copy(id = "two", title = "VpnKavkaz с длинным названием подписки",
            source = "https://second.example/sub", webPageUrl = "https://second.example/account",
            supportUrl = "https://second.example/help", announcement = "Сообщение второго",
            servers = listOf(ServerProfile("same", "Второй", "vless", raw = "second")))
        val key = Subscription("key", "Ключ", "vless://example",
            listOf(ServerProfile("same", "Ключ", "vless", raw = "vless://example")))
        val links = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ServersPage(listOf(phantom, other, key), phantom to phantom.servers.single(),
                        null, emptySet(), emptyMap(), emptySet(), emptyMap(), emptySet(), emptySet(),
                        {}, {}, {}, {}, { _, _ -> }, {}, { links.add(it) })
                }
            }
        }
        fun clickDescription(description: String) {
            val match = hasContentDescription(description)
            compose.onNode(hasScrollAction()).performScrollToNode(match)
            compose.onNode(match).performClick()
        }
        for (owner in listOf(phantom, other)) {
            if (owner == other) clickDescription("Раскрыть подписку ${owner.title}")
            clickDescription("Подробнее о ${owner.title}")
            clickDescription("Свернуть подписку ${owner.title}")
            compose.onNodeWithText(owner.announcement!!).assertIsDisplayed()
            compose.onNodeWithContentDescription("Выбрать сервер ${owner.servers.first().name}").assertDoesNotExist()
            clickDescription("Кабинет ${owner.title}")
            clickDescription("Поддержка ${owner.title}")
            clickDescription("Подробнее о ${owner.title}")
            compose.onNodeWithText(owner.announcement!!).assertDoesNotExist()
        }
        assertEquals(listOf(phantom.webPageUrl, phantom.supportUrl, other.webPageUrl, other.supportUrl), links)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ключ"))
        compose.onAllNodesWithText("Ключ").assertCountEquals(1)
        compose.onNodeWithContentDescription("Свернуть подписку Ключ").assertDoesNotExist()
    }
}
