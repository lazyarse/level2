package io.securitycam.level1.detection.person

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Letterbox geometry for mapping a frame into the model's 640x640 input. */
data class LetterboxInfo(val gain: Double, val padX: Int, val padY: Int)

/**
 * Letterbox geometry: how a [width]x[height] frame is mapped into the model's
 * 640x640 input (uniform scale + centered black padding). Port of the Dart
 * `letterboxInfo` (including its `-0.1` rounding nudge).
 */
fun letterboxInfo(width: Int, height: Int): LetterboxInfo {
    val gain = min(640.0 / width, 640.0 / height)
    val newW = (width * gain).roundToInt()
    val newH = (height * gain).roundToInt()
    val padX = ((640 - newW) / 2.0 - 0.1).roundToInt()
    val padY = ((640 - newH) / 2.0 - 0.1).roundToInt()
    return LetterboxInfo(gain, padX, padY)
}

/**
 * Decodes YOLO26n `[1, 84, 8400]` float32 output (person class = row 4) into
 * person boxes in original frame coordinates. Box rows are normalized [0,1];
 * scores are sigmoid-activated by the graph. Applies the confidence gate,
 * letterbox undo, clamping, then IoU NMS.
 */
fun decodeYolo26(
    output: FloatArray,
    conf: Double,
    iou: Double,
    maxDetections: Int,
    frameWidth: Int,
    frameHeight: Int,
): List<PersonBox> {
    val anchors = 8400
    val boxRows = 4

    val info = letterboxInfo(frameWidth, frameHeight)
    val candidates = mutableListOf<PersonBox>()
    for (i in 0 until anchors) {
        val score = output[boxRows * anchors + i].toDouble()
        if (score < conf) continue
        val cx = output[i].toDouble()
        val cy = output[anchors + i].toDouble()
        val w = output[2 * anchors + i].toDouble()
        val h = output[3 * anchors + i].toDouble()
        val x1m = (cx - w / 2) * 640
        val y1m = (cy - h / 2) * 640
        val x2m = (cx + w / 2) * 640
        val y2m = (cy + h / 2) * 640
        candidates.add(
            PersonBox(
                clamp((x1m - info.padX) / info.gain, 0.0, frameWidth.toDouble()),
                clamp((y1m - info.padY) / info.gain, 0.0, frameHeight.toDouble()),
                clamp((x2m - info.padX) / info.gain, 0.0, frameWidth.toDouble()),
                clamp((y2m - info.padY) / info.gain, 0.0, frameHeight.toDouble()),
                score,
            ),
        )
    }
    candidates.sortByDescending { it.score }
    return nms(candidates, iou = iou, maxDetections = maxDetections)
}

/**
 * Non-max suppression over score-descending [boxes]; keeps at most
 * [maxDetections] boxes that don't overlap a kept box beyond [iou].
 */
fun nms(boxes: List<PersonBox>, iou: Double, maxDetections: Int): List<PersonBox> {
    val kept = mutableListOf<PersonBox>()
    for (b in boxes) {
        var overlap = false
        for (k in kept) {
            if (iouOf(b, k) > iou) {
                overlap = true
                break
            }
        }
        if (!overlap) {
            kept.add(b)
            if (kept.size >= maxDetections) break
        }
    }
    return kept
}

/** Intersection-over-union of two boxes. */
fun iouOf(a: PersonBox, b: PersonBox): Double {
    val ix = max(0.0, min(a.x2, b.x2) - max(a.x1, b.x1))
    val iy = max(0.0, min(a.y2, b.y2) - max(a.y1, b.y1))
    val inter = ix * iy
    val union =
        (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
    return if (union <= 0) 0.0 else inter / union
}

private fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v