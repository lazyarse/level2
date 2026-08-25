package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.FrameDetector
import io.securitycam.level2.detection.RegionFilter

/**
 * Livestock-detection trigger (cow/sheep/horse). Runs on color analysis frames
 * (motion-gated by the pipeline, like
 * [io.securitycam.level2.detection.person.PersonDetector]).
 *
 * Uses the shared YOLO26n model via [YoloLivestockEngine] — zero extra model
 * load when the person detector is also enabled.
 */
class LivestockDetector(
    override val config: DetectorConfig,
    engine: LivestockEngine? = null,
) : FrameDetector() {

    private val engine: LivestockEngine = engine ?: YoloLivestockEngine(AppContextHolder.require())
    private var persistenceCount = 0

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.livestock

    override suspend fun init() {
        engine.init()
    }

    override fun reset() {
        persistenceCount = 0
    }

    override suspend fun dispose() {
        engine.dispose()
    }

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val color = frame.color ?: return result(frame.timestamp, 0.0, false)
        var animals = engine.detectLivestock(color)
        if (animals.isNotEmpty()) {
            animals = animals.filter { p ->
                val bx = p.x1 / color.width
                val by = p.y1 / color.height
                val bw = (p.x2 - p.x1) / color.width
                val bh = (p.y2 - p.y1) / color.height
                RegionFilter.rectOverlapsAny(regions, bx, by, bw, bh) &&
                    !RegionFilter.boxHitsAnyExclusion(exclusionRegions, bx, by, bw, bh)
            }
        }
        if (animals.isEmpty()) {
            persistenceCount = 0
            return result(frame.timestamp, 0.0, false)
        }
        val maxScore = animals.maxOf { it.score }
        val above = maxScore >= config.threshold
        persistenceCount = if (above) persistenceCount + 1 else 0
        if (persistenceCount >= config.persistenceFrames) {
            persistenceCount = 0
            return result(frame.timestamp, maxScore, true)
        }
        return result(frame.timestamp, maxScore, false)
    }

    private fun result(ts: java.time.Instant, score: Double, triggered: Boolean): DetectionResult =
        DetectionResult(
            timestamp = ts,
            triggerType = triggerType,
            score = score,
            triggered = triggered,
        )
}
