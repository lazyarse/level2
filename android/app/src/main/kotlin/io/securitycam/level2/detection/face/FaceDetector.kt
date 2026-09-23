package io.securitycam.level2.detection.face

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.ColorBitmap
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.ZoneFilteredDetector
import io.securitycam.level2.detection.person.AppContextHolder

/**
 * Face-detection trigger. Runs on color analysis frames (motion-gated by the
 * pipeline). Persistence/threshold/cooldown come from [DetectorConfig]; faces
 * are zone-filtered via box overlap when zones are set.
 *
 * Detection is async, so the real work lives in [analyzeFrameAsync];
 * [analyzeFrame] is a no-op non-trigger for the sync path.
 */
open class FaceDetector(
    override val config: DetectorConfig,
    engine: FaceEngine? = null,
) : ZoneFilteredDetector() {

    private val engine: FaceEngine = engine ?: MediaPipeFaceEngine(AppContextHolder.require())

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.face

    override suspend fun init() {
        engine.init()
    }

    override suspend fun dispose() {
        engine.dispose()
    }

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val top = topFace(frame)
        if (top == null) {
            persistenceCount = 0
            return result(frame.timestamp, 0.0, false)
        }
        val (_, best) = top
        val (score, triggered, _) = gate(best.score, present = true)
        return result(frame.timestamp, score, triggered)
    }

    /** Highest-confidence zone-filtered face, with its frame; null if none. */
    protected suspend fun topFace(frame: AnalysisFrame): Pair<ColorBitmap, FaceDetection>? {
        val color = frame.color ?: return null
        // Boxes are already normalized 0..1 per the FaceDetection contract.
        val faces = engine.detectFaces(color).filter { f ->
            val bw = f.x2 - f.x1
            val bh = f.y2 - f.y1
            // Keep when it overlaps an inclusion zone (or none exist) and no
            // exclusion zone: exclusion wins.
            keepBox(f.x1, f.y1, bw, bh)
        }
        val best = faces.maxByOrNull { it.score } ?: return null
        return color to best
    }
}