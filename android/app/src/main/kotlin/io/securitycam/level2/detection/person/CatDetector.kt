package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.audio.AudioEventScores
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.HybridDetector
import io.securitycam.level2.detection.ZoneFilter

/**
 * Combined cat trigger: fires on SIGHT (YOLO box) or SOUND (the fused
 * meow/purr/hiss/caterwaul score), whichever crosses its threshold first.
 * Separate visual ([DetectorConfig.threshold]) and audio
 * ([DetectorConfig.audioThreshold]) thresholds with independent persistence
 * counters; fired results carry the modality in `detail` ("seen"/"meow").
 *
 * Visual engine is the shared YOLO model — zero extra model load
 * when the person detector is also enabled.
 */
class CatDetector(
    override val config: DetectorConfig,
    visualEngine: YoloObjectEngine? = null,
) : HybridDetector() {

    private val engine: YoloObjectEngine = visualEngine ?: YoloObjectEngineImpl(AppContextHolder.require(), listOf(YoloClasses.CAT))
    private var audioStreak = 0

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.cat

    override suspend fun init() {
        engine.init()
    }

    override fun reset() {
        super.reset()
        audioStreak = 0
    }

    override suspend fun dispose() {
        engine.dispose()
    }

    // ---- sight ----

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false, detail = null)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val color = frame.color ?: return result(frame.timestamp, 0.0, false, detail = null)
        val cats = keepPixelBoxes(engine.detect(color), color.width, color.height)
        latestBoxes = cats
        val outcome = if (cats.isEmpty()) {
            gate(0.0, present = false)
        } else {
            gate(cats.maxOf { it.score }, present = true, fireDetail = DETAIL_SEEN)
        }
        return result(frame.timestamp, outcome.score, outcome.triggered, detail = outcome.detail)
    }

    // ---- sound ----

    override fun analyzeScores(scores: AudioEventScores): DetectionResult {
        val soundScore = scores.scoreOf("cat")
        val threshold = config.audioThreshold ?: config.threshold
        val above = soundScore >= threshold
        audioStreak = if (above) audioStreak + 1 else 0
        if (audioStreak >= config.persistenceFrames) {
            audioStreak = 0
            return result(scores.timestamp, soundScore, true, detail = DETAIL_MEOW)
        }
        return result(scores.timestamp, soundScore, false, detail = null)
    }


    companion object {
        const val DETAIL_SEEN = "seen"
        const val DETAIL_MEOW = "meow"
    }
}
