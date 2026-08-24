package io.securitycam.level1.detection.audio

import io.securitycam.level1.detection.AudioWindow
import java.time.Instant
import kotlin.math.sqrt

/** Per-class scores from an audio event classifier. */
data class AudioEventScores(
    val timestamp: Instant,
    val classScores: Map<String, Double>,
) {
    fun scoreOf(label: String): Double = classScores[label] ?: 0.0
}

/** Audio event classifier contract (port of `lib/detection/audio/audio_classifier.dart`). */
interface AudioEventClassifier {
    val id: String

    suspend fun init()

    suspend fun classify(window: AudioWindow): AudioEventScores

    suspend fun dispose()
}

/**
 * RMS/ZCR-based mock classifier used off-device and as a fallback (port of
 * `MockAudioEventClassifier`). No model required.
 */
class MockAudioEventClassifier : AudioEventClassifier {
    override val id: String get() = "mock"

    override suspend fun init() {}

    override suspend fun dispose() {}

    override suspend fun classify(window: AudioWindow): AudioEventScores {
        val rms = rms(window.samples)
        val zcr = zeroCrossingRate(window.samples)
        val babyCry = if (rms > 0.02 && zcr < 0.08) scale(rms, 0.02, 0.3) else 0.0
        val glass = if (rms > 0.08 && zcr > 0.25) scale(rms, 0.08, 0.5) else 0.0
        val loudNoise = if (rms > 0.45 && zcr > 0.35) scale(rms, 0.45, 0.6) else 0.0
        val dogBark = if (rms > 0.05 && zcr in 0.12..0.30) scale(rms, 0.05, 0.4) else 0.0
        val growl = if (rms > 0.08 && zcr < 0.10) scale(rms, 0.08, 0.35) else 0.0
        val cat = if (rms > 0.03 && zcr in 0.04..0.18) scale(rms, 0.03, 0.25) else 0.0
        return AudioEventScores(
            timestamp = window.timestamp,
            classScores = mapOf(
                "baby_cry" to babyCry,
                "glass" to glass,
                "loud_noise" to loudNoise,
                "dog_bark" to dogBark,
                "growl" to growl,
                "cat" to cat,
            ),
        )
    }

    private fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size)
    }

    private fun zeroCrossingRate(samples: FloatArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++
        }
        return crossings.toDouble() / samples.size
    }

    private fun scale(value: Double, floor: Double, ceil: Double): Double {
        val v = (value - floor) / (ceil - floor)
        return v.coerceIn(0.0, 1.0)
    }
}