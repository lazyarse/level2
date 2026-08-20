package io.securitycam.level1.detection

import io.securitycam.level1.core.TriggerType
import java.time.Instant

private const val PIXEL_DIFF_TOLERANCE = 30

/**
 * Grayscale pixel-diff motion detector (port of
 * `lib/detection/motion_detector.dart`). First frame primes; consecutive
 * above-threshold ratio frames for [DetectorConfig.persistenceFrames] trigger.
 */
class MotionDetector(
    override val config: io.securitycam.level1.detection.DetectorConfig,
) : FrameDetector() {

    private var previous: GrayscaleBitmap? = null
    private var persistenceCount = 0
    private var mask: ByteArray? = null
    private var maskCount = 0
    private var maskWidth = 0
    private var maskHeight = 0
    private var maskRegions: List<DetectionRegion>? = null

    override val id: String get() = config.type
    override val triggerType: String get() = TriggerType.motion

    override suspend fun init() {}

    override fun reset() {
        previous = null
        persistenceCount = 0
        mask = null
    }

    override suspend fun dispose() {}

    override fun analyzeFrame(frame: AnalysisFrame): DetectionResult {
        if (mask == null ||
            maskRegions !== regions ||
            maskWidth != frame.bitmap.width ||
            maskHeight != frame.bitmap.height
        ) {
            rebuildMask(frame.bitmap.width, frame.bitmap.height)
        }
        val bitmap = frame.bitmap
        val prev = previous
        previous = bitmap
        if (prev == null) {
            return result(frame.timestamp, 0.0, false)
        }
        val ratio = diffRatio(prev, bitmap)
        val triggered = updatePersistence(ratio, frame.timestamp)
        return result(frame.timestamp, ratio, triggered)
    }

    private fun rebuildMask(width: Int, height: Int) {
        val (newMask, count) = RegionFilter.pixelMask(regions, width, height)
        mask = newMask
        maskCount = count
        maskRegions = regions
        maskWidth = width
        maskHeight = height
    }

    private fun updatePersistence(ratio: Double, timestamp: Instant): Boolean {
        val above = ratio >= config.threshold
        persistenceCount = if (above) persistenceCount + 1 else 0
        if (persistenceCount >= config.persistenceFrames) {
            persistenceCount = 0
            return true
        }
        return false
    }

    private fun diffRatio(a: GrayscaleBitmap, b: GrayscaleBitmap): Double {
        val mask = mask ?: return 0.0
        val count = maskCount
        var changed = 0
        for (y in 0 until a.height) {
            val rowA = y * a.width
            for (x in 0 until a.width) {
                val idx = rowA + x
                if (mask[idx].toInt() == 0) continue
                val diff = (a.gray[idx].toInt() and 0xFF) - (b.gray[idx].toInt() and 0xFF)
                if (kotlin.math.abs(diff) > PIXEL_DIFF_TOLERANCE) changed++
            }
        }
        return if (count == 0) 0.0 else changed.toDouble() / count
    }

    private fun result(ts: Instant, score: Double, triggered: Boolean): DetectionResult =
        DetectionResult(
            timestamp = ts,
            triggerType = triggerType,
            score = score,
            triggered = triggered,
        )
}

/** Test helper: uniform gray frame. */
fun buildFrame(width: Int, height: Int, fill: Int): ByteArray {
    val buf = ByteArray(width * height)
    buf.fill(fill.toByte())
    return buf
}

/** Test helper: frame with a filled rectangle. */
fun buildFrameWithRect(
    width: Int, height: Int, fill: Int,
    rectX: Int, rectY: Int, rectW: Int, rectH: Int, rectFill: Int,
): ByteArray {
    val buf = buildFrame(width, height, fill)
    for (y in rectY until rectY + rectH) {
        if (y >= height) break
        for (x in rectX until rectX + rectW) {
            if (x >= width) break
            buf[y * width + x] = rectFill.toByte()
        }
    }
    return buf
}