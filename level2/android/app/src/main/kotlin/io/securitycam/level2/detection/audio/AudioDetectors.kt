package io.securitycam.level2.detection.audio

import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.AudioDetector
import io.securitycam.level2.detection.DetectionResult

/**
 * Persistence-based audio detectors over scored classes (ports of the Dart
 * `baby_cry_detector.dart` / `glass_break_detector.dart` /
 * `loud_noise_detector.dart`). A score at/above the threshold for
 * [DetectorConfig.persistenceFrames] consecutive windows triggers, then re-arms.
 */
abstract class PersistenceAudioDetector(
    override val config: DetectorConfig,
    private val scoreLabel: String,
) : AudioDetector() {

    private var persistenceCount = 0

    override val id: String get() = config.type

    override suspend fun init() {}

    override fun reset() {
        persistenceCount = 0
    }

    override suspend fun dispose() {}

    override fun analyzeScores(scores: AudioEventScores): DetectionResult {
        val score = scores.scoreOf(scoreLabel)
        val triggered = updatePersistence(score)
        return DetectionResult(
            timestamp = scores.timestamp,
            triggerType = triggerType,
            score = score,
            triggered = triggered,
        )
    }

    private fun updatePersistence(score: Double): Boolean {
        val above = score >= config.threshold
        persistenceCount = if (above) persistenceCount + 1 else 0
        if (persistenceCount >= config.persistenceFrames) {
            persistenceCount = 0
            return true
        }
        return false
    }
}

class BabyCryDetector(config: DetectorConfig) :
    PersistenceAudioDetector(config, "baby_cry") {
    override val triggerType: String get() = TriggerType.babyCry
}

class GlassBreakDetector(config: DetectorConfig) :
    PersistenceAudioDetector(config, "glass") {
    override val triggerType: String get() = TriggerType.glassBreak
}

class LoudNoiseDetector(config: DetectorConfig) :
    PersistenceAudioDetector(config, "loud_noise") {
    override val triggerType: String get() = TriggerType.loudNoise
}