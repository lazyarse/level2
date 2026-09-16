package io.securitycam.level2.detection

import io.securitycam.level2.core.TriggerType
import kotlin.math.roundToInt

/**
 * User-facing 1–20 sensitivity scale for detectors.
 *
 * Internally detectors still compare `score >= [DetectorConfig.threshold]`
 * (a 0–1 confidence/pixel-ratio), which is meaningless to most users. The
 * settings UI converts between the two and the stored JSON keeps the raw
 * threshold, so old blobs load unchanged and the detection pipeline is
 * untouched.
 *
 * Direction: 20 = most sensitive (lowest internal threshold, most triggers).
 * Mapping is linear within a per-detector threshold band, inverted:
 * `threshold = max − (s − 1) / 19 · (max − min)`.
 *
 * Bands exist because detectors live on very different scales: motion's
 * default (0.03 pixel-ratio) would bunch up at one end of a global map, so
 * it gets its own narrow band while every confidence-based detector shares
 * the classifier band.
 */
object SensitivityScale {
    const val MIN = 1
    const val MAX = 20

    private data class Band(val minThreshold: Double, val maxThreshold: Double)

    /** Motion is a changed-pixel ratio; its useful span is far below 0.5. */
    private val motionBand = Band(minThreshold = 0.005, maxThreshold = 0.20)

    /** Every confidence-based detector (vision, audio, hybrid sound). */
    private val classifierBand = Band(minThreshold = 0.10, maxThreshold = 0.90)

    private fun bandFor(type: String): Band =
        if (type == TriggerType.motion) motionBand else classifierBand

    /** Sensitivity 1–20 → internal threshold. Inputs are clamped. */
    fun sensitivityToThreshold(type: String, sensitivity: Int): Double {
        val band = bandFor(type)
        val s = sensitivity.coerceIn(MIN, MAX)
        return band.maxThreshold -
            (s - MIN).toDouble() / (MAX - MIN) *
            (band.maxThreshold - band.minThreshold)
    }

    /** Internal threshold → nearest sensitivity 1–20. Out-of-band values clamp. */
    fun thresholdToSensitivity(type: String, threshold: Double): Int {
        val band = bandFor(type)
        val t = threshold.coerceIn(band.minThreshold, band.maxThreshold)
        return (
            MIN +
                (band.maxThreshold - t) / (band.maxThreshold - band.minThreshold) *
                (MAX - MIN)
            ).roundToInt().coerceIn(MIN, MAX)
    }

    /** Short qualitative caption for a sensitivity value. */
    fun label(sensitivity: Int): String = when (sensitivity.coerceIn(MIN, MAX)) {
        in MIN..6 -> "Low"
        in 7..14 -> "Medium"
        else -> "High"
    }
}
