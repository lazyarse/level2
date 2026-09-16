package io.securitycam.level2.core

import java.time.Instant

/** Typed settings for a channel; subclasses know their secret fields. */
abstract class ChannelSettings {
    abstract val type: String

    abstract fun toJson(): Map<String, Any?>

    abstract val secretFields: List<String>
}

/** Per-channel alert frequency: how often a continuous wave may re-notify. */
object AlertMode {
    /** Every trigger sends (the per-trigger fast path default). */
    const val EVERY_TRIGGER = "every_trigger"

    /** Only the wave's first trigger sends; joiners are DB-merged silently. */
    const val PER_WAVE = "per_wave"

    /** First trigger sends, then at most one repeat per [ChannelConfig.alertEverySeconds]. */
    const val THROTTLED = "throttled"

    /** Default throttle interval for fresh channels. */
    const val DEFAULT_EVERY_SECONDS = 60
}

/** Serializable channel configuration (port of `lib/core/channel.dart`). */
data class ChannelConfig(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val settingsJson: Map<String, Any?> = emptyMap(),
    /** Push a short GIF preview of the recorded clip once it is muxed. */
    val pushVideoPreview: Boolean = false,
    /** User-facing account name; blank falls back to a derived "Type N" name. */
    val label: String = "",
    /** One of [AlertMode]; unknown (forward-version) values behave as [AlertMode.EVERY_TRIGGER]. */
    val alertMode: String = AlertMode.EVERY_TRIGGER,
    /** Minimum seconds between repeat alerts in [AlertMode.THROTTLED]; ignored otherwise. */
    val alertEverySeconds: Int = 60,
) {
    fun copyWith(
        enabled: Boolean? = null,
        settingsJson: Map<String, Any?>? = null,
        pushVideoPreview: Boolean? = null,
        label: String? = null,
        alertMode: String? = null,
        alertEverySeconds: Int? = null,
    ): ChannelConfig = ChannelConfig(
        id = id,
        type = type,
        enabled = enabled ?: this.enabled,
        settingsJson = settingsJson ?: this.settingsJson,
        pushVideoPreview = pushVideoPreview ?: this.pushVideoPreview,
        label = label ?: this.label,
        alertMode = alertMode ?: this.alertMode,
        alertEverySeconds = alertEverySeconds ?: this.alertEverySeconds,
    )

    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "enabled" to enabled,
        "settings" to settingsJson,
        "pushVideoPreview" to pushVideoPreview,
        "label" to label,
        "alertMode" to alertMode,
        "alertEverySeconds" to alertEverySeconds,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>): ChannelConfig = ChannelConfig(
            id = json["id"] as String,
            type = json["type"] as String,
            enabled = json["enabled"] as? Boolean ?: true,
            settingsJson = (json["settings"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key as String to it.value } ?: emptyMap(),
            pushVideoPreview = json["pushVideoPreview"] as? Boolean ?: false,
            label = json["label"] as? String ?: "",
            alertMode = json["alertMode"] as? String ?: AlertMode.EVERY_TRIGGER,
            alertEverySeconds = (json["alertEverySeconds"] as? Number)?.toInt()
                ?: AlertMode.DEFAULT_EVERY_SECONDS,
        )
    }
}

/**
 * Pure throttle decision for the per-trigger fast path. [lastSentMs] is null
 * until this wave's first send to the channel; [nowMs] is the send_tests clock
 * (System.currentTimeMillis in production, fake in tests).
 */
fun ChannelConfig.isDue(lastSentMs: Long?, nowMs: Long): Boolean = when (alertMode) {
    AlertMode.PER_WAVE -> lastSentMs == null
    AlertMode.THROTTLED -> {
        if (lastSentMs == null) true
        else if (alertEverySeconds <= 0) true
        else nowMs - lastSentMs >= alertEverySeconds * 1000L
    }
    // EVERY_TRIGGER and any unknown forward-version mode: always send.
    else -> true
}

/** Alert payload delivered through a channel. */
data class AlertMessage(
    val timestamp: Instant,
    val triggerType: String,
    val text: String,
    val snapshot: Snapshot? = null,
    /** Optional GIF preview of the recorded clip (pushed once the clip muxes). */
    val videoPreview: Snapshot? = null,
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

/**
 * True for never-configured placeholder accounts: one of the multi-account
 * types, disabled, unlabeled, with no real settings (webhook allows a lone
 * preset key — the shape of the old shipped `discord` default). Fresh
 * installs no longer ship these, and loads/saves drop them so legacy empty
 * cards disappear. Anything the user touched (enabled, labelled, or holding
 * settings) is kept.
 */
internal fun ChannelConfig.isPristinePlaceholder(): Boolean {
    if (type !in setOf("email", "telegram", "pushover", "webhook")) return false
    if (enabled || label.isNotBlank()) return false
    if (settingsJson.isEmpty()) return true
    return type == "webhook" && settingsJson.keys.all { it == "preset" }
}

/**
 * Whether a channel can receive the video-preview GIF. Types without a
 * file/attachment path (slack/teams/custom webhooks, log) skip the preview
 * push entirely — their text alert already went out at batch close.
 */
fun ChannelConfig.supportsVideoPreview(): Boolean = when (type) {
    "telegram", "email", "pushover" -> true
    "webhook" -> preset() in setOf("discord", "ntfy") ||
        (preset() == "custom" && settingsJson["attachPhotos"] == true)
    else -> false
}

/** Webhook preset (`settingsJson["preset"]`), defaulting to "custom". */
fun ChannelConfig.preset(): String = settingsJson["preset"] as? String ?: "custom"