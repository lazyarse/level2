package io.securitycam.level2.camera_service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.media3.effect.BitmapOverlay
import io.securitycam.level2.detection.DetectionRegion
import io.securitycam.level2.detection.DetectionRegionShape

/**
 * Full-frame overlay that obscures exclusion zones in exported clips.
 * Coordinates are rotated by [clipRotation] so the mask matches the
 * upright/display space even though the overlay is drawn in pre-rotation
 * pixel space.
 *
 * The [effect] controls rendering:
 * - [PrivacyMaskEffect.solid] — semi-transparent dark fill (no source pixels needed)
 * - [PrivacyMaskEffect.pixelate] — mosaic blocks from [sourceFrames]
 * - [PrivacyMaskEffect.blur] — box blur from [sourceFrames]
 *
 * When [sourceFrames] is null or empty, pixelate/blur fall back to solid.
 */
class PrivacyMaskOverlay(
    private val exclusionRegions: List<DetectionRegion>,
    private val effect: String,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val clipRotation: Int,
    private val sourceFrames: Map<Long, Bitmap>? = null,
) : BitmapOverlay() {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun getBitmap(presentationUs: Long): Bitmap {
        val bmp = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val srcFrame = sourceFrames?.get(nearestKey(sourceFrames, presentationUs))

        for (region in exclusionRegions) {
            when (effect) {
                "pixelate" -> drawPixelatedRegion(canvas, region, srcFrame)
                "blur" -> drawBlurredRegion(canvas, region, srcFrame)
                else -> drawSolidRegion(canvas, region)
            }
        }
        return bmp
    }

    /** Semi-transparent dark fill for a single exclusion region. */
    private fun drawSolidRegion(canvas: Canvas, region: DetectionRegion) {
        when (region.shape) {
            DetectionRegionShape.rect -> {
                val (x0, y0) = rotated(region.points[0], region.points[1])
                val (x1, y1) = rotated(region.points[2], region.points[3])
                val left = minOf(x0, x1) * frameWidth
                val top = minOf(y0, y1) * frameHeight
                val right = maxOf(x0, x1) * frameWidth
                val bottom = maxOf(y0, y1) * frameHeight
                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), maskPaint)
            }
            DetectionRegionShape.poly -> {
                canvas.drawPath(regionPath(region), maskPaint)
            }
        }
    }

    /** Pixelated (mosaic) rendering for a single exclusion region. */
    private fun drawPixelatedRegion(canvas: Canvas, region: DetectionRegion, srcFrame: Bitmap?) {
        if (srcFrame == null) { drawSolidRegion(canvas, region); return }
        val path = regionPath(region)
        val bounds = RectF()
        path.computeBounds(bounds, true)

        val blockSize = 12
        val left = (bounds.left.toInt() / blockSize) * blockSize
        val top = (bounds.top.toInt() / blockSize) * blockSize
        val right = ((bounds.right.toInt() + blockSize - 1) / blockSize) * blockSize
        val bottom = ((bounds.bottom.toInt() + blockSize - 1) / blockSize) * blockSize

        val blockPaint = Paint()
        for (bx in left until right step blockSize) {
            for (by in top until bottom step blockSize) {
                val cx = bx + blockSize / 2
                val cy = by + blockSize / 2
                if (cx < frameWidth && cy < frameHeight && cx >= 0 && cy >= 0) {
                    val sampleX = cx * srcFrame.width / frameWidth
                    val sampleY = cy * srcFrame.height / frameHeight
                    val color = srcFrame.getPixel(
                        sampleX.coerceIn(0, srcFrame.width - 1),
                        sampleY.coerceIn(0, srcFrame.height - 1),
                    )
                    blockPaint.color = color
                    canvas.drawRect(
                        bx.toFloat(), by.toFloat(),
                        (bx + blockSize).toFloat(), (by + blockSize).toFloat(),
                        blockPaint,
                    )
                }
            }
        }
        // Overlay semi-transparent border for clarity
        canvas.drawPath(path, maskPaint)
    }

    /** Box-blur rendering for a single exclusion region. */
    private fun drawBlurredRegion(canvas: Canvas, region: DetectionRegion, srcFrame: Bitmap?) {
        if (srcFrame == null) { drawSolidRegion(canvas, region); return }
        val path = regionPath(region)
        val bounds = RectF()
        path.computeBounds(bounds, true)

        val radius = 10
        val blurPaint = Paint()
        val left = bounds.left.toInt().coerceIn(0, frameWidth - 1)
        val top = bounds.top.toInt().coerceIn(0, frameHeight - 1)
        val right = bounds.right.toInt().coerceIn(0, frameWidth - 1)
        val bottom = bounds.bottom.toInt().coerceIn(0, frameHeight - 1)

        for (x in left..right step 2) {
            for (y in top..bottom step 2) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dx in -radius..radius step 2) {
                    for (dy in -radius..radius step 2) {
                        val sx = (x + dx) * srcFrame.width / frameWidth
                        val sy = (y + dy) * srcFrame.height / frameHeight
                        if (sx in 0 until srcFrame.width && sy in 0 until srcFrame.height) {
                            val c = srcFrame.getPixel(sx, sy)
                            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                            count++
                        }
                    }
                }
                if (count > 0) {
                    blurPaint.color = Color.rgb(r / count, g / count, b / count)
                    canvas.drawRect(x.toFloat(), y.toFloat(), (x + 2).toFloat(), (y + 2).toFloat(), blurPaint)
                }
            }
        }
        canvas.drawPath(path, maskPaint)
    }

    /**
     * Rotate normalised (x, y) from upright/display space into pre-rotation
     * pixel space using the clip's rotation metadata.
     */
    private fun rotated(x: Double, y: Double): Pair<Double, Double> = when (clipRotation) {
        90  -> y to 1.0 - x
        180 -> 1.0 - x to 1.0 - y
        270 -> 1.0 - y to x
        else -> x to y
    }

    /** Build a [Path] from a polygon region's rotated normalised points. */
    private fun regionPath(region: DetectionRegion): Path {
        val path = Path()
        when (region.shape) {
            DetectionRegionShape.rect -> {
                val (x0, y0) = rotated(region.points[0], region.points[1])
                val (x1, y1) = rotated(region.points[2], region.points[3])
                path.addRect(
                    RectF(
                        minOf(x0, x1).toFloat() * frameWidth,
                        minOf(y0, y1).toFloat() * frameHeight,
                        maxOf(x0, x1).toFloat() * frameWidth,
                        maxOf(y0, y1).toFloat() * frameHeight,
                    ),
                    Path.Direction.CW,
                )
            }
            DetectionRegionShape.poly -> {
                val pts = region.points
                if (pts.size >= 4) {
                    val (sx, sy) = rotated(pts[0], pts[1])
                    path.moveTo(sx.toFloat() * frameWidth, sy.toFloat() * frameHeight)
                    var i = 2
                    while (i + 1 < pts.size) {
                        val (px, py) = rotated(pts[i], pts[i + 1])
                        path.lineTo(px.toFloat() * frameWidth, py.toFloat() * frameHeight)
                        i += 2
                    }
                    path.close()
                }
            }
        }
        return path
    }
}

/** Find the key in [map] closest to [target]. */
private fun nearestKey(map: Map<Long, *>, target: Long): Long {
    var best = map.keys.first()
    var bestDist = Long.MAX_VALUE
    for (k in map.keys) {
        val d = kotlin.math.abs(k - target)
        if (d < bestDist) { best = k; bestDist = d }
    }
    return best
}
