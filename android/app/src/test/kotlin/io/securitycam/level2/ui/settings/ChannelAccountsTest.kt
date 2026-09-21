package io.securitycam.level2.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Webhook
import io.securitycam.level2.core.ChannelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for multi-account channel helpers (id allocation, naming). */
class ChannelAccountsTest {

    @Test
    fun nextFreeIdUsesBareTypeWhenFree() {
        assertEquals("email", nextFreeChannelId("email", emptySet()))
    }

    @Test
    fun nextFreeIdSkipsTakenIds() {
        assertEquals("email-2", nextFreeChannelId("email", setOf("email")))
        assertEquals(
            "email-3",
            nextFreeChannelId("email", setOf("email", "email-2", "telegram")),
        )
    }

    @Test
    fun displayNameFallsBackToDerivedIndex() {
        val solo = ChannelConfig(id = "email", type = "email")
        assertEquals("Email", channelDisplayName(solo, listOf(solo)))

        val first = ChannelConfig(id = "email", type = "email")
        val second = ChannelConfig(id = "email-2", type = "email")
        val siblings = listOf(first, second)
        assertEquals("Email", channelDisplayName(first, siblings))
        assertEquals("Email 2", channelDisplayName(second, siblings))
    }

    @Test
    fun displayNamePrefersCustomLabel() {
        val config = ChannelConfig(id = "email-2", type = "email", label = "Work")
        val siblings = listOf(ChannelConfig(id = "email", type = "email"), config)
        assertEquals("Work", channelDisplayName(config, siblings))
    }

    @Test
    fun labelRoundTripsThroughJson() {
        val config = ChannelConfig(id = "email-2", type = "email", label = "Work")
        val restored = ChannelConfig.fromJson(config.toJson())
        assertEquals("Work", restored.label)
        assertEquals("email-2", restored.id)
        assertEquals("email", restored.type)
    }

    @Test
    fun channelIconResolvesAllTypes() {
        assertEquals(Icons.Filled.Email, channelIcon("email"))
        assertEquals(Icons.Filled.Send, channelIcon("telegram"))
        assertEquals(Icons.Filled.Webhook, channelIcon("webhook"))
        assertEquals(Icons.Filled.Notifications, channelIcon("pushover"))
        assertEquals(Icons.Filled.Terminal, channelIcon("log"))
        assertEquals(Icons.Filled.NotificationImportant, channelIcon("nope"))
    }

}
