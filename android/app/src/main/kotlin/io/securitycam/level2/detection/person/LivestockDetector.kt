package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.ZoneFilteredDetector

/**
 * Livestock-detection trigger (cow/sheep/horse). Runs on color analysis frames
 * (motion-gated by the pipeline, like
 * [io.securitycam.level2.detection.person.PersonDetector]).
 *
 * Uses the shared YOLO26n model via [YoloYoloObjectEngine] — zero extra model
 * load when the person detector is also enabled.
 */
class LivestockDetector(
    override val config: DetectorConfig,
    engine: YoloObjectEngine? = null,
) : ZoneFilteredDetector() {

    private val engine: YoloObjectEngine = engine ?: YoloObjectEngineImpl(AppContextHolder.require(), YoloClasses.LIVESTOCK)
    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.livestock

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
        val animals = keepPixelBoxes(engine.detect(color), color.width, color.height)
        latestBoxes = animals
        val outcome = if (animals.isEmpty()) {
            gate(0.0, present = false)
        } else {
            gate(animals.maxOf { it.score }, present = true)
        }
        return result(frame.timestamp, outcome.score, outcome.triggered)
    }

}
