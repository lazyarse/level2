package io.securitycam.level2.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.ui.graphics.vector.ImageVector
import io.securitycam.level2.channels.EmailChannelSettings
import io.securitycam.level2.channels.PushoverChannelSettings
import io.securitycam.level2.channels.TelegramChannelSettings
import io.securitycam.level2.channels.WebhookChannelSettings

/** Channel type ids (replaces magic strings in Settings UI). */
internal object ChannelTypes {
    const val TELEGRAM = "telegram"
    const val EMAIL = "email"
    const val WEBHOOK = "webhook"
    const val PUSHOVER = "pushover"
    const val LOG = "log"
}

/** LiveView stream modes. */
internal object LiveViewModes {
    const val SERVER = "server"
    const val PUSH = "push"
}

/** Cloud backup backends. */
internal object CloudBackends {
    const val WEBDAV = "webdav"
    const val S3 = "s3"
}

/** Webhook preset + body-style values. */
internal object WebhookValues {
    const val CUSTOM = "custom"
    const val NTFY = "ntfy"
    const val JSON = "json"
    const val TEXT = "text"
}

/** Channel types that support multiple accounts (add/delete in Settings). */
internal val multiAccountTypes = setOf(
    ChannelTypes.EMAIL,
    ChannelTypes.TELEGRAM,
    ChannelTypes.PUSHOVER,
    ChannelTypes.WEBHOOK,
)

/** Builds channel configs from field state at save time (Dart `_save`). */
internal fun buildChannelConfigs(
    configs: List<io.securitycam.level2.core.ChannelConfig>,
    fields: Map<String, String>,
): List<io.securitycam.level2.core.ChannelConfig> = configs.map { c ->
    fun f(key: String): String = fields["${c.id}.$key"] ?: ""
    when (c.type) {
        ChannelTypes.TELEGRAM -> c.copy(
            settingsJson = TelegramChannelSettings(
                botToken = f("token"),
                chatId = f("chat"),
            ).toJson(),
        )

        ChannelTypes.EMAIL -> c.copy(
            settingsJson = EmailChannelSettings(
                host = f("host").trim(),
                port = f("port").trim().toIntOrNull() ?: 587,
                username = f("username").trim(),
                // Trimmed: copy-paste habitually trails whitespace, which the
                // server rejects with a 535; no real password needs it.
                password = f("password").trim(),
                from = f("from").trim(),
                to = f("to").trim(),
                useTls = f("tls") == "1",
            ).toJson(),
        )

        ChannelTypes.WEBHOOK -> c.copy(
            settingsJson = WebhookChannelSettings(
                preset = f("preset").ifEmpty { WebhookValues.CUSTOM },
                url = f("url").trim(),
                bearerToken = f("token"),
                title = f("title"),
                bodyStyle = f("bodystyle").ifEmpty { WebhookValues.JSON },
            ).toJson(),
        )

        ChannelTypes.PUSHOVER -> c.copy(
            settingsJson = PushoverChannelSettings(
                appToken = f("appToken").trim(),
                userKey = f("userKey").trim(),
                sound = f("sound").trim(),
                priority = f("priority").trim().toIntOrNull() ?: 0,
                retrySeconds = f("retrySeconds").trim().toIntOrNull() ?: 60,
                expireSeconds = f("expireSeconds").trim().toIntOrNull() ?: 3600,
            ).toJson(),
        )

        else -> c
    }
}

/**
 * Port check the channel validators don't see: [buildChannelConfigs] silently
 * falls back to 587 on garbage input, so flag it here instead. Null (or
 * blank, which also means 587) is fine.
 */
internal fun emailPortError(channelId: String, fields: Map<String, String>): String? {
    val raw = fields["$channelId.port"]?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val port = raw.toIntOrNull()
    return if (port == null || port !in 1..65535) "Port must be a number from 1 to 65535" else null
}

/** Type glyph for channel cards (mirrors the DetectorCard title-icon pattern). */
internal fun channelIcon(type: String): ImageVector =
    when (type) {
        ChannelTypes.EMAIL -> Icons.Filled.Email
        ChannelTypes.TELEGRAM -> Icons.Filled.Send
        ChannelTypes.WEBHOOK -> Icons.Filled.Webhook
        ChannelTypes.PUSHOVER -> Icons.Filled.Notifications
        ChannelTypes.LOG -> Icons.Filled.Terminal
        else -> Icons.Filled.NotificationImportant
    }

/** Channel header display name: raw type ids rendered Title Case. */
internal fun channelTitle(type: String): String = type.split('_', ' ', '-')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/**
 * Account display name: the custom label when set, else the type title with
 * a same-type index suffix past the first ("Email", "Email 2", …).
 */
internal fun channelDisplayName(
    config: io.securitycam.level2.core.ChannelConfig,
    siblings: List<io.securitycam.level2.core.ChannelConfig>,
): String {
    if (config.label.isNotBlank()) return config.label
    val index = siblings.filter { it.type == config.type }
        .sortedBy { it.id }
        .indexOfFirst { it.id == config.id }
    val base = channelTitle(config.type)
    return if (index <= 0) base else "$base ${index + 1}"
}

/** Next free account id for [type]: bare `<type>`, else `<type>-2`, `-3`, … */
internal fun nextFreeChannelId(
    type: String,
    existingIds: Set<String>,
): String {
    if (type !in existingIds) return type
    var n = 2
    while ("$type-$n" in existingIds) n++
    return "$type-$n"
}

/** Drops [channelId] from every detector's channel routes (account deletion). */
internal fun pruneChannelFromDetectors(
    detectors: Map<String, io.securitycam.level2.detection.DetectorConfig>,
    channelId: String,
): Map<String, io.securitycam.level2.detection.DetectorConfig> =
    detectors.mapValues { (_, config) ->
        if (channelId in config.routeToChannelIds) {
            config.copy(routeToChannelIds = config.routeToChannelIds - channelId)
        } else {
            config
        }
    }
