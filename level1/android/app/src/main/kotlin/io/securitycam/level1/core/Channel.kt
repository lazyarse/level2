package io.securitycam.level1.core

import java.time.Instant

/** Typed settings for a channel; subclasses know their secret fields. */
abstract class ChannelSettings {
    abstract val type: String

    abstract fun toJson(): Map<String, Any?>

    abstract val secretFields: List<String>
}

/** Serializable channel configuration (port of `lib/core/channel.dart`). */
data class ChannelConfig(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val settingsJson: Map<String, Any?> = emptyMap(),
) {
    fun copyWith(
        enabled: Boolean? = null,
        settingsJson: Map<String, Any?>? = null,
    ): ChannelConfig = ChannelConfig(
        id = id,
        type = type,
        enabled = enabled ?: this.enabled,
        settingsJson = settingsJson ?: this.settingsJson,
    )

    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "enabled" to enabled,
        "settings" to settingsJson,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>): ChannelConfig = ChannelConfig(
            id = json["id"] as String,
            type = json["type"] as String,
            enabled = json["enabled"] as? Boolean ?: true,
            settingsJson = (json["settings"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key as String to it.value } ?: emptyMap(),
        )
    }
}

/** Alert payload delivered through a channel. */
data class AlertMessage(
    val timestamp: Instant,
    val triggerType: String,
    val text: String,
    val snapshot: Snapshot? = null,
)

/** Delivery contract (port of `lib/core/channel.dart` `Channel`). */
interface Channel {
    val id: String
    val type: String
    val enabled: Boolean
    val settings: ChannelSettings

    suspend fun send(message: AlertMessage)

    suspend fun sendTest()

    fun validate(): String?
}

data class ChannelDeliveryResult(
    val channelId: String,
    val status: String,
)