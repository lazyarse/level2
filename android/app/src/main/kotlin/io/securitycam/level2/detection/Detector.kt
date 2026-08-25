package io.securitycam.level2.detection

import io.securitycam.level2.detection.audio.AudioEventScores
import java.time.Duration

/**
 * Detector configuration (port of `lib/core/detector.dart` `DetectorConfig`).
 * JSON keys match the Dart blob.
 */
data class DetectorConfig(
    val type: String,
    val enabled: Boolean = true,
    val threshold: Double = 0.5,
    val persistenceFrames: Int = 2,
    val cooldown: Duration = Duration.ofSeconds(5),
    val routeToChannelIds: List<String> = emptyList(),
    val motionGated: Boolean = false,
    /** Loitering only: seconds of continuous presence before firing. */
    val dwellSeconds: Int = 10,
    /**
     * Combined pet detectors only: threshold for the sound modality. When
     * null the visual [threshold] governs both.
     */
    val audioThreshold: Double? = null,
) {
    fun copyWith(
        type: String? = null,
        enabled: Boolean? = null,
        threshold: Double? = null,
        persistenceFrames: Int? = null,
        cooldown: Duration? = null,
        routeToChannelIds: List<String>? = null,
        motionGated: Boolean? = null,
        dwellSeconds: Int? = null,
        audioThreshold: Double? = null,
    ): DetectorConfig = DetectorConfig(
        type = type ?: this.type,
        enabled = enabled ?: this.enabled,
        threshold = threshold ?: this.threshold,
        persistenceFrames = persistenceFrames ?: this.persistenceFrames,
        cooldown = cooldown ?: this.cooldown,
        routeToChannelIds = routeToChannelIds ?: this.routeToChannelIds,
        motionGated = motionGated ?: this.motionGated,
        dwellSeconds = dwellSeconds ?: this.dwellSeconds,
        audioThreshold = audioThreshold ?: this.audioThreshold,
    )

    fun toJson(): Map<String, Any?> = mapOf(
        "type" to type,
        "enabled" to enabled,
        "threshold" to threshold,
        "persistenceFrames" to persistenceFrames,
        "cooldownMs" to cooldown.toMillis(),
        "routeToChannelIds" to routeToChannelIds,
        "motionGated" to motionGated,
        "dwellSeconds" to dwellSeconds,
        "audioThreshold" to audioThreshold,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>): DetectorConfig = DetectorConfig(
            type = json["type"] as String,
            enabled = json["enabled"] as? Boolean ?: true,
            threshold = (json["threshold"] as? Number)?.toDouble() ?: 0.5,
            persistenceFrames = (json["persistenceFrames"] as? Number)?.toInt() ?: 2,
            cooldown = Duration.ofMillis(
                (json["cooldownMs"] as? Number)?.toLong() ?: 5_000L,
            ),
            routeToChannelIds = (json["routeToChannelIds"] as? List<*>)
                ?.map { it as String }
                ?: emptyList(),
            motionGated = json["motionGated"] as? Boolean ?: false,
            dwellSeconds = (json["dwellSeconds"] as? Number)?.toInt() ?: 10,
            audioThreshold = (json["audioThreshold"] as? Number)?.toDouble(),
        )
    }
}

/** Base detector contract (port of `lib/core/detector.dart`). */
interface Detector {
    val id: String
    val config: DetectorConfig
    val triggerType: String

    suspend fun init()

    fun reset()

    suspend fun dispose()
}

/** Frame-based detector; [regions]/[exclusionRegions] are set by the pipeline. */
abstract class FrameDetector : Detector {
    var regions: List<DetectionRegion> = emptyList()
    var exclusionRegions: List<DetectionRegion> = emptyList()

    abstract fun analyzeFrame(frame: AnalysisFrame): DetectionResult

    /** Async analysis path for gated/heavy detectors; defaults to the sync path. */
    open suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult =
        analyzeFrame(frame)
}

/** Score-based audio detector. */
abstract class AudioDetector : Detector {
    abstract fun analyzeScores(scores: AudioEventScores): DetectionResult
}

/**
 * A frame detector that also reacts to classifier scores (combined pet
 * detectors: sight OR sound). Registered under one config; the pipeline feeds
 * it frames through [analyzeFrameAsync] and audio windows through
 * [analyzeScores], each with its own persistence counter.
 */
abstract class HybridDetector : FrameDetector() {
    abstract fun analyzeScores(scores: AudioEventScores): DetectionResult
}