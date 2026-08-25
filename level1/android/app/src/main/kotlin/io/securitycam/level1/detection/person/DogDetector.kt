package io.securitycam.level1.detection.person

import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.AnalysisFrame
import io.securitycam.level1.detection.audio.AudioEventScores
import io.securitycam.level1.detection.DetectionResult
import io.securitycam.level1.detection.DetectorConfig
import io.securitycam.level1.detection.FrameDetector
import io.securitycam.level1.detection.HybridDetector
import io.securitycam.level1.detection.RegionFilter

/**
 * Combined dog trigger: fires on SIGHT (YOLO box) or SOUND (bark/growl from
 * the YAMNet score vector), whichever crosses its threshold first. One toggle,
 * one cooldown; separate visual ([DetectorConfig.threshold]) and audio
 * ([DetectorConfig.audioThreshold]) thresholds with independent persistence
 * counters. Fired results carry the modality in `detail`
 * ("seen"/"bark"/"growl") so events read "Dog detected in Hallway (bark)".
 *
 * Visual engine is the shared [YoloPersonEngine] model — zero extra model load.
 */
class DogDetector(
    override val config: DetectorConfig,
    visualEngine: DogEngine? = null,
) : HybridDetector() {

    private val engine: DogEngine = visualEngine ?: YoloDogEngine(AppContextHolder.require())
    private var visualStreak = 0
    private var audioStreak = 0

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.dog

    override suspend fun init() {
        engine.init()
    }

    override fun reset() {
        visualStreak = 0
        audioStreak = 0
    }

    override suspend fun dispose() {
        engine.dispose()
    }

    // ---- sight ----

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult =
        result(frame.timestamp, 0.0, false, null)

    override suspend fun analyzeFrameAsync(frame: AnalysisFrame): DetectionResult {
        val color = frame.color ?: return result(frame.timestamp, 0.0, false, null)
        var dogs = engine.detectDogs(color)
        if (dogs.isNotEmpty()) {
            dogs = dogs.filter { p ->
                val bx = p.x1 / color.width
                val by = p.y1 / color.height
                val bw = (p.x2 - p.x1) / color.width
                val bh = (p.y2 - p.y1) / color.height
                RegionFilter.rectOverlapsAny(regions, bx, by, bw, bh) &&
                    !RegionFilter.boxHitsAnyExclusion(exclusionRegions, bx, by, bw, bh)
            }
        }
        if (dogs.isEmpty()) {
            visualStreak = 0
            return result(frame.timestamp, 0.0, false, null)
        }
        val maxScore = dogs.maxOf { it.score }
        val above = maxScore >= config.threshold
        visualStreak = if (above) visualStreak + 1 else 0
        if (visualStreak >= config.persistenceFrames) {
            visualStreak = 0
            return result(frame.timestamp, maxScore, true, DETAIL_SEEN)
        }
        return result(frame.timestamp, maxScore, false, null)
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
            return result(scores.timestamp, soundScore, true, detail)
        }
        return result(scores.timestamp, soundScore, false, null)
    }

    private fun result(
        ts: java.time.Instant,
        score: Double,
        triggered: Boolean,
        detail: String?,
    ): DetectionResult =
        DetectionResult(
            timestamp = ts,
            triggerType = triggerType,
            score = score,
            triggered = triggered,
            detail = detail,
        )

    companion object {
        const val DETAIL_SEEN = "seen"
        const val DETAIL_BARK = "bark"
        const val DETAIL_GROWL = "growl"
    }
}
