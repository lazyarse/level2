package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.ZoneFilteredDetector

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
) : ZoneFilteredDetector() {

    private val engine: PersonEngine = engine ?: YoloPersonEngine(AppContextHolder.require())

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.person

    override suspend fun init() {
        engine.init()
    }

    override suspend fun dispose() {
        engine.dispose()
    }

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val color = frame.color ?: return result(frame.timestamp, 0.0, false)
        val people = keepPixelBoxes(engine.detectPersons(color), color.width, color.height)
        latestBoxes = people
        // Keep when the box overlaps an inclusion zone (or none exist) and
        // no exclusion zone: exclusion wins (see [keepBox]).
        val outcome = if (people.isEmpty()) {
            gate(0.0, present = false)
        } else {
            gate(people.maxOf { it.score }, present = true)
        }
        return result(frame.timestamp, outcome.score, outcome.triggered)
    }
}