package io.securitycam.level2.channels

import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.ChannelSettings

/** Factory for a channel from its config. */
typealias ChannelFactory = (ChannelConfig) -> io.securitycam.level2.core.Channel

/** Registry of channel factories keyed by channel type. */
object ChannelRegistry {
    private const val TAG = "ChannelRegistry"
    private data class ChannelEntry(
        val factory: ChannelFactory,
        val settingsBuilder: (Map<String, Any?>) -> ChannelSettings,
    )

    private val entries: Map<String, ChannelEntry> = linkedMapOf(
        "log" to ChannelEntry(
            factory = { c -> LogChannel(id = c.id, enabled = c.enabled) },
            settingsBuilder = { _ -> LogChannelSettings },
        ),
        "telegram" to ChannelEntry(
            factory = { c ->
                TelegramChannel(
                    id = c.id,
                    enabled = c.enabled,
                    settings = TelegramChannelSettings.fromJson(c.settingsJson),
                )
            },
            settingsBuilder = { json -> TelegramChannelSettings.fromJson(json) },
        ),
        "email" to ChannelEntry(
            factory = { c ->
                EmailChannel(
                    id = c.id,
                    enabled = c.enabled,
                    settings = EmailChannelSettings.fromJson(c.settingsJson),
                )
            },
            settingsBuilder = { json -> EmailChannelSettings.fromJson(json) },
        ),
        "webhook" to ChannelEntry(
            factory = { c ->
                WebhookChannel(
                    id = c.id,
                    enabled = c.enabled,
                    settings = WebhookChannelSettings.fromJson(c.settingsJson),
                )
            },
            settingsBuilder = { json -> WebhookChannelSettings.fromJson(json) },
        ),
        "pushover" to ChannelEntry(
            factory = { c ->
                PushoverChannel(
                    id = c.id,
                    enabled = c.enabled,
                    settings = PushoverChannelSettings.fromJson(c.settingsJson),
                )
            },
            settingsBuilder = { json -> PushoverChannelSettings.fromJson(json) },
        ),
    )

    val factories: Map<String, ChannelFactory> =
        entries.mapValues { (_, entry) -> entry.factory }

    fun factoryFor(type: String): ChannelFactory? = factories[type]

    /**
     * Builds the typed [ChannelSettings] for a channel type (used by the
     * settings store to know which fields are secrets, and by the UI).
     * Returns null (fail soft) for forward-version unknown types so callers
     * can route through the misconfigured path instead of crashing.
     */
    fun buildChannelSettings(type: String, json: Map<String, Any?>): ChannelSettings? =
        entries[type]?.settingsBuilder?.invoke(json) ?: run {
            android.util.Log.w(TAG, "unsupported channel type: $type")
            null
        }
}