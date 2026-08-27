package io.securitycam.level2.detection.person

import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AnalysisFrame
import io.securitycam.level2.detection.audio.AudioEventScores
import io.securitycam.level2.detection.DetectionResult
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.HybridDetector
import io.securitycam.level2.detection.RegionFilter

/**
 * Combined cat trigger: fires on SIGHT (YOLO box) or SOUND (the fused
 * meow/purr/hiss/caterwaul score), whichever crosses its threshold first.
 * Separate visual ([DetectorConfig.threshold]) and audio
 * ([DetectorConfig.audioThreshold]) thresholds with independent persistence
 * counters; fired results carry the modality in `detail` ("seen"/"meow").
 *
 * Visual engine is the shared [YoloPersonEngine] model — zero extra model load
 * when the person detector is also enabled.
 */
class CatDetector(
    override val config: DetectorConfig,
    visualEngine: CatEngine? = null,
) : HybridDetector() {

    private val engine: CatEngine = visualEngine ?: YoloCatEngine(AppContextHolder.require())
    private var visualStreak = 0
    private var audioStreak = 0

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.cat

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
        var cats = engine.detectCats(color)
        if (cats.isNotEmpty()) {
            cats = cats.filter { p ->
                val bx = p.x1 / color.width
                val by = p.y1 / color.height
                val bw = (p.x2 - p.x1) / color.width
                val bh = (p.y2 - p.y1) / color.height
                RegionFilter.rectOverlapsAny(regions, bx, by, bw, bh) &&
                    !RegionFilter.boxHitsAnyExclusion(exclusionRegions, bx, by, bw, bh)
            }
        }
        latestBoxes = cats
        if (cats.isEmpty()) {
            visualStreak = 0
            return result(frame.timestamp, 0.0, false, null)
        }
        val maxScore = cats.maxOf { it.score }
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
        val soundScore = scores.scoreOf("cat")
        val threshold = config.audioThreshold ?: config.threshold
        val above = soundScore >= threshold
        audioStreak = if (above) audioStreak + 1 else 0
        if (audioStreak >= config.persistenceFrames) {
            audioStreak = 0
            return result(scores.timestamp, soundScore, true, DETAIL_MEOW)
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
        const val DETAIL_MEOW = "meow"
    }
}
