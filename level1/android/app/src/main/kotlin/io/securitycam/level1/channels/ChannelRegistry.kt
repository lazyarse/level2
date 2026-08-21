package io.securitycam.level1.channels

import io.securitycam.level1.core.ChannelConfig
import io.securitycam.level1.core.ChannelSettings

/** Factory for a channel from its config (port of `lib/core/registries.dart`). */
typealias ChannelFactory = (ChannelConfig) -> io.securitycam.level1.core.Channel

/** Registry of channel factories keyed by channel type. */
object ChannelRegistry {
    val factories: Map<String, ChannelFactory> = linkedMapOf(
        "log" to { c -> LogChannel(id = c.id, enabled = c.enabled) },
        "telegram" to { c ->
            TelegramChannel(
                id = c.id,
                enabled = c.enabled,
                settings = TelegramChannelSettings.fromJson(c.settingsJson),
            )
        },
        "email" to { c ->
            EmailChannel(
                id = c.id,
                enabled = c.enabled,
                settings = EmailChannelSettings.fromJson(c.settingsJson),
            )
        },
        "webhook" to { c ->
            WebhookChannel(
                id = c.id,
                enabled = c.enabled,
                settings = WebhookChannelSettings.fromJson(c.settingsJson),
            )
        },
        "pushover" to { c ->
            PushoverChannel(
                id = c.id,
                enabled = c.enabled,
                settings = PushoverChannelSettings.fromJson(c.settingsJson),
            )
        },
    )

    fun factoryFor(type: String): ChannelFactory? = factories[type]

    /**
     * Builds the typed [ChannelSettings] for a channel type (used by the
     * settings store to know which fields are secrets, and by the UI).
     */
    fun buildChannelSettings(type: String, json: Map<String, Any?>): ChannelSettings =
        when (type) {
            "log" -> LogChannelSettings
            "telegram" -> TelegramChannelSettings.fromJson(json)
            "email" -> EmailChannelSettings.fromJson(json)
            "webhook" -> WebhookChannelSettings.fromJson(json)
            "pushover" -> PushoverChannelSettings.fromJson(json)
            else -> throw IllegalArgumentException("unsupported channel type: $type")
        }
}