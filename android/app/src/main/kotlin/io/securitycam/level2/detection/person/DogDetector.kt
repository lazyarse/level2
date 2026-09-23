package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.audio.AudioEventScores
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.HybridDetector
import io.securitycam.level2.detection.ZoneFilter

/**
 * Combined dog trigger: fires on SIGHT (YOLO box) or SOUND (bark/growl from
 * the YAMNet score vector), whichever crosses its threshold first. One toggle,
 * one cooldown; separate visual ([DetectorConfig.threshold]) and audio
 * ([DetectorConfig.audioThreshold]) thresholds with independent persistence
 * counters. Fired results carry the modality in `detail`
 * ("seen"/"bark"/"growl") so events read "Dog detected in Hallway (bark)".
 *
 * Visual engine is the shared YOLO model — zero extra model load.
 */
class DogDetector(
    override val config: DetectorConfig,
    visualEngine: YoloObjectEngine? = null,
) : HybridDetector() {

    private val engine: YoloObjectEngine = visualEngine ?: YoloObjectEngineImpl(AppContextHolder.require(), listOf(YoloClasses.DOG))
    private var audioStreak = 0

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.dog

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
        val dogs = keepPixelBoxes(engine.detect(color), color.width, color.height)
        latestBoxes = dogs
        val outcome = if (dogs.isEmpty()) {
            gate(0.0, present = false)
        } else {
            gate(dogs.maxOf { it.score }, present = true, fireDetail = DETAIL_SEEN)
        }
        return result(frame.timestamp, outcome.score, outcome.triggered, detail = outcome.detail)
    }

    // ---- sound ----

    override fun analyzeScores(scores: AudioEventScores): DetectionResult {
        val bark = scores.scoreOf("dog_bark")
        val growl = scores.scoreOf("growl")
        val soundScore = maxOf(bark, growl)
        val threshold = config.audioThreshold ?: config.threshold
        val above = soundScore >= threshold
        audioStreak = if (above) audioStreak + 1 else 0
        if (audioStreak >= config.persistenceFrames) {
            audioStreak = 0
            val detail = if (bark >= growl) DETAIL_BARK else DETAIL_GROWL
            return result(scores.timestamp, soundScore, true, detail = detail)
        }
        return result(scores.timestamp, soundScore, false, detail = null)
    }


    companion object {
        const val DETAIL_SEEN = "seen"
        const val DETAIL_BARK = "bark"
        const val DETAIL_GROWL = "growl"
    }
}
