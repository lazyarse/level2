package io.securitycam.level1.detection.face

import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.AnalysisFrame
import io.securitycam.level1.detection.ColorBitmap
import io.securitycam.level1.detection.DetectionResult
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.detection.FrameDetector
import io.securitycam.level1.detection.RegionFilter
import io.securitycam.level1.detection.person.AppContextHolder

/**
 * Face-detection trigger. Runs on color analysis frames (motion-gated by the
 * pipeline). Persistence/threshold/cooldown come from [DetectorConfig]; faces
 * are region-filtered via box overlap when regions are set.
 *
 * Detection is async, so the real work lives in [analyzeFrameAsync];
 * [analyzeFrame] is a no-op non-trigger for the sync path.
 */
open class FaceDetector(
    override val config: DetectorConfig,
    engine: FaceEngine? = null,
) : FrameDetector() {

    private val engine: FaceEngine = engine ?: MediaPipeFaceEngine(AppContextHolder.require())
    private var persistenceCount = 0

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.face

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
        val top = topFace(frame)
        if (top == null) {
            persistenceCount = 0
            return result(frame.timestamp, 0.0, false)
        }
        val (color, best) = top
        val above = best.score >= config.threshold
        persistenceCount = if (above) persistenceCount + 1 else 0
        if (persistenceCount >= config.persistenceFrames) {
            persistenceCount = 0
            return result(frame.timestamp, best.score, true)
        }
        return result(frame.timestamp, best.score, false)
    }

    /** Highest-confidence region-filtered face, with its frame; null if none. */
    protected suspend fun topFace(frame: AnalysisFrame): Pair<ColorBitmap, FaceDetection>? {
        val color = frame.color ?: return null
        var faces = engine.detectFaces(color)
        faces = faces.filter { f ->
            val bx = f.x1 / color.width
            val by = f.y1 / color.height
            val bw = (f.x2 - f.x1) / color.width
            val bh = (f.y2 - f.y1) / color.height
            // Keep when it overlaps an inclusion zone (or none exist) and no
            // exclusion zone: exclusion wins.
            RegionFilter.rectOverlapsAny(regions, bx, by, bw, bh) &&
                !RegionFilter.boxHitsAnyExclusion(exclusionRegions, bx, by, bw, bh)
        }
        val best = faces.maxByOrNull { it.score } ?: return null
        return color to best
    }

    private fun result(ts: java.time.Instant, score: Double, triggered: Boolean): DetectionResult =
        DetectionResult(
            timestamp = ts,
            triggerType = triggerType,
            score = score,
            triggered = triggered,
        )
}