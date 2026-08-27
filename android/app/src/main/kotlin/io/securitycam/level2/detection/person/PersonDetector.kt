package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.FrameDetector
import io.securitycam.level2.detection.RegionFilter

/**
 * Person-detection trigger. Runs on color analysis frames (motion-gated by the
 * pipeline). Persistence/threshold/cooldown come from [DetectorConfig].
 *
 * Detection is async (LiteRT inference), so the real work lives in
 * [analyzeFrameAsync]; [analyzeFrame] is a no-op non-trigger for the sync path.
 */
class PersonDetector(
    override val config: DetectorConfig,
    engine: PersonEngine? = null,
) : FrameDetector() {

    private val engine: PersonEngine = engine ?: YoloPersonEngine(AppContextHolder.require())
    private var persistenceCount = 0

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.person

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
        var people = engine.detectPersons(color)
        if (people.isNotEmpty()) {
            // Keep when the box overlaps an inclusion zone (or none exist) and
            // no exclusion zone: exclusion wins.
            people = people.filter { p ->
                val bx = p.x1 / color.width
                val by = p.y1 / color.height
                val bw = (p.x2 - p.x1) / color.width
                val bh = (p.y2 - p.y1) / color.height
                RegionFilter.rectOverlapsAny(regions, bx, by, bw, bh) &&
                    !RegionFilter.boxHitsAnyExclusion(exclusionRegions, bx, by, bw, bh)
            }
        }
        latestBoxes = people
        if (people.isEmpty()) {
            persistenceCount = 0
            return result(frame.timestamp, 0.0, false)
        }
        val maxScore = people.maxOf { it.score }
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