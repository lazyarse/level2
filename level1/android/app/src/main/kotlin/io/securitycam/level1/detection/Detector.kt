package io.securitycam.level1.detection

import io.securitycam.level1.detection.audio.AudioEventScores
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
    val cooldown: Duration = Duration.ofSeconds(60),
    val routeToChannelIds: List<String> = emptyList(),
    val motionGated: Boolean = false,
) {
    fun copyWith(
        type: String? = null,
        enabled: Boolean? = null,
        threshold: Double? = null,
        persistenceFrames: Int? = null,
        cooldown: Duration? = null,
        routeToChannelIds: List<String>? = null,
        motionGated: Boolean? = null,
    ): DetectorConfig = DetectorConfig(
        type = type ?: this.type,
        enabled = enabled ?: this.enabled,
        threshold = threshold ?: this.threshold,
        persistenceFrames = persistenceFrames ?: this.persistenceFrames,
        cooldown = cooldown ?: this.cooldown,
        routeToChannelIds = routeToChannelIds ?: this.routeToChannelIds,
        motionGated = motionGated ?: this.motionGated,
    )

    fun toJson(): Map<String, Any?> = mapOf(
        "type" to type,
        "enabled" to enabled,
        "threshold" to threshold,
        "persistenceFrames" to persistenceFrames,
        "cooldownMs" to cooldown.toMillis(),
        "routeToChannelIds" to routeToChannelIds,
        "motionGated" to motionGated,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>): DetectorConfig = DetectorConfig(
            type = json["type"] as String,
            enabled = json["enabled"] as? Boolean ?: true,
            threshold = (json["threshold"] as? Number)?.toDouble() ?: 0.5,
            persistenceFrames = (json["persistenceFrames"] as? Number)?.toInt() ?: 2,
            cooldown = Duration.ofMillis(
                (json["cooldownMs"] as? Number)?.toLong() ?: 60_000L,
            ),
            routeToChannelIds = (json["routeToChannelIds"] as? List<*>)
                ?.map { it as String }
                ?: emptyList(),
            motionGated = json["motionGated"] as? Boolean ?: false,
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