package io.securitycam.level2.detection

import io.securitycam.level2.detection.audio.AudioEventScores
import io.securitycam.level2.detection.DetectedBox
import java.time.Duration

/**
 * Detector configuration.
 * JSON keys are stable so stored blobs keep parsing.
 */
data class DetectorConfig(
    val type: String,
    val enabled: Boolean = true,
    val threshold: Double = 0.5,
    val persistenceFrames: Int = 2,
    val cooldown: Duration = Duration.ofSeconds(5),
    /** Loitering only: seconds of continuous presence before firing. */
    val dwellSeconds: Int = 10,
    /**
     * Combined pet detectors only: threshold for the sound modality. When
     * null the visual [threshold] governs both.
     */
    val audioThreshold: Double? = null,
    /** Tripwire detector: which target classes to detect crossing. */
    val tripwireTargets: List<String> = listOf("person"),
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "type" to type,
        "enabled" to enabled,
        "threshold" to threshold,
        "persistenceFrames" to persistenceFrames,
        "cooldownMs" to cooldown.toMillis(),
        "dwellSeconds" to dwellSeconds,
        "audioThreshold" to audioThreshold,
        "tripwireTargets" to tripwireTargets,
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
            dwellSeconds = (json["dwellSeconds"] as? Number)?.toInt() ?: 10,
            audioThreshold = (json["audioThreshold"] as? Number)?.toDouble(),
            tripwireTargets = (json["tripwireTargets"] as? List<*>)?.map { it as String }
                ?: listOf("person"),
        )
    }
}

/** Base detector contract. */
interface Detector {
    val id: String
    val config: DetectorConfig
    val triggerType: String

    suspend fun init()

    fun reset()

    suspend fun dispose()
}

/** Frame-based detector; [zones]/[exclusionZones] are set by the pipeline. */
abstract class FrameDetector : Detector {
    var zones: List<DetectionZone> = emptyList()
    var exclusionZones: List<DetectionZone> = emptyList()

    /** Boxes from the most recent analysis frame, updated by subclasses for sharing. */
    open var latestBoxes: List<DetectedBox> = emptyList()

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
 * Frame detector with zone filtering, threshold/persistence gating, and
 * result construction shared by every box-based detector. Subclasses keep
 * only engine wiring and any custom logic (dwell timing, audio paths,
 * crossing tracks); the visual streak always lives here.
 */
abstract class ZoneFilteredDetector : FrameDetector() {
    protected var persistenceCount = 0

    override fun reset() {
        persistenceCount = 0
    }

    /** Zone predicate on normalized box coords; exclusion wins. */
    protected fun keepBox(nx: Double, ny: Double, nw: Double, nh: Double): Boolean =
        ZoneFilter.rectOverlapsAny(zones, nx, ny, nw, nh) &&
            !ZoneFilter.boxHitsAnyExclusion(exclusionZones, nx, ny, nw, nh)

    /** [keepBox] over a pixel-space [DetectedBox] list. */
    protected fun keepPixelBoxes(
        boxes: List<DetectedBox>,
        frameW: Int,
        frameH: Int,
    ): List<DetectedBox> = boxes.filter { p ->
        keepBox(
            p.x1 / frameW,
            p.y1 / frameH,
            (p.x2 - p.x1) / frameW,
            (p.y2 - p.y1) / frameH,
        )
    }

    protected data class GateOutcome(
        val score: Double,
        val triggered: Boolean,
        val detail: String?,
    )

    /**
     * Threshold/persistence gate. Misses reset the streak; firing resets it
     * too and carries [fireDetail].
     */
    protected fun gate(
        score: Double,
        present: Boolean,
        fireDetail: String? = null,
    ): GateOutcome {
        if (!present) {
            persistenceCount = 0
            return GateOutcome(0.0, false, null)
        }
        val above = score >= config.threshold
        persistenceCount = if (above) persistenceCount + 1 else 0
        return if (persistenceCount >= config.persistenceFrames) {
            persistenceCount = 0
            GateOutcome(score, true, fireDetail)
        } else {
            GateOutcome(score, false, null)
        }
    }

    protected fun result(
        ts: java.time.Instant,
        score: Double,
        triggered: Boolean,
        detail: String? = null,
        triggerType: String = config.type,
        detectorId: String? = null,
    ): DetectionResult = DetectionResult(
        timestamp = ts,
        triggerType = triggerType,
        score = score,
        triggered = triggered,
        detail = detail,
        detectorId = detectorId,
    )
}

/**
 * A frame detector that also reacts to classifier scores (combined pet
 * detectors: sight OR sound). Registered under one config; the pipeline feeds
 * it frames through [analyzeFrameAsync] and audio windows through
 * [analyzeScores]; the visual streak lives in [ZoneFilteredDetector], audio
 * keeps its own counter.
 */
abstract class HybridDetector : ZoneFilteredDetector() {
    abstract fun analyzeScores(scores: AudioEventScores): DetectionResult
}