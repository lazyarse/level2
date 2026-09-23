package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.ZoneFilteredDetector

/**
 * Vehicle-detection trigger (car/motorcycle/bus/truck). Runs on color analysis
 * frames, motion-gated by the pipeline like
 * [io.securitycam.level2.detection.person.PersonDetector].
 *
 * Uses the shared YOLO26n model via [YoloVehicleEngine] — zero extra model
 * load when the person detector is also enabled.
 */
class VehicleDetector(
    override val config: DetectorConfig,
    engine: VehicleEngine? = null,
) : ZoneFilteredDetector() {

    private val engine: VehicleEngine = engine ?: YoloVehicleEngine(AppContextHolder.require())
    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.vehicle

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
        val vehicles = keepPixelBoxes(engine.detectVehicles(color), color.width, color.height)
        latestBoxes = vehicles
        val outcome = if (vehicles.isEmpty()) {
            gate(0.0, present = false)
        } else {
            gate(vehicles.maxOf { it.score }, present = true)
        }
        return result(frame.timestamp, outcome.score, outcome.triggered)
    }

}
